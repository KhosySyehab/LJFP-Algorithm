#!/usr/bin/env python3
"""
ljfp_scheduler.py — LJFP Real-World Scheduler (Kelompok 6)

Mengeksekusi workload nyata di atas container Docker menggunakan
algoritma LJFP (Longest Job to Fastest Processor).

Arsitektur:
  container_fastest  → --cpus=2.0 (mewakili VM-Fastest,  MIPS relatif 4000)
  container_medium   → --cpus=1.0 (mewakili VM-Standard, MIPS relatif 2000)
  container_slowest  → --cpus=0.5 (mewakili VM-Eco,      MIPS relatif 1000)

Workload:
  Setiap task dieksekusi sebagai proses Python komputasi intensif
  (iterasi matematis yang membebani CPU secara proporsional terhadap
  Length_MI dari dataset).

Cara menjalankan:
  1. docker compose up -d           (di folder realworld/)
  2. python3 ljfp_scheduler.py      (dari folder realworld/)
  3. Pantau CPU via: http://localhost:3000 (Grafana)
"""

import csv
import subprocess
import time
import threading
import json
import os
from datetime import datetime

# ── Konfigurasi Container ─────────────────────────────────────────────────────
# "speed" adalah proxy kapasitas CPU relative (analogous to MIPS)
CONTAINERS = [
    {"name": "container_fastest",  "speed": 4.0, "cpus": 2.0},
    {"name": "container_medium",   "speed": 2.0, "cpus": 1.0},
    {"name": "container_slowest",  "speed": 1.0, "cpus": 0.5},
]

# Faktor konversi MI ke detik eksekusi nyata
# 1 MI = 0.00001 detik di container_fastest (speed=4.0)
MI_TO_SECONDS_BASE = 0.00001

# Jumlah task yang dijalankan di real-world (kurangi untuk demo cepat)
MAX_REAL_TASKS = 30

DATASET_PATH = "workload/dataset_500tasks.csv"
RESULTS_PATH = "results_realworld.csv"


# ── Dataset Loader ────────────────────────────────────────────────────────────

def load_tasks(csv_path: str, max_tasks: int) -> list[dict]:
    """Membaca dataset CSV dan mengembalikan list task."""
    tasks = []
    with open(csv_path, newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            tasks.append({
                "id":     int(row["Task_ID"]),
                "length": int(row["Length_MI"]),
            })
            if len(tasks) >= max_tasks:
                break

    # LJFP Step 1: Sort DESCENDING by Length (task terpanjang didahulukan)
    tasks.sort(key=lambda t: t["length"], reverse=True)
    print(f"  Loaded {len(tasks)} tasks | Longest: {tasks[0]['length']:,} MI | Shortest: {tasks[-1]['length']:,} MI")
    return tasks


# ── LJFP Scheduling Logic ─────────────────────────────────────────────────────

def ljfp_assign(tasks: list[dict]) -> list[tuple[dict, dict]]:
    """
    LJFP Greedy Assignment:
    Untuk setiap task (sudah sorted descending by length),
    pilih container dengan Estimated Completion Time (ECT) terkecil.
    """
    availability = {c["name"]: 0.0 for c in CONTAINERS}
    assignments = []

    for task in tasks:
        best_container = None
        min_ect = float("inf")

        for c in CONTAINERS:
            exec_time = (task["length"] / c["speed"]) * MI_TO_SECONDS_BASE
            ect = availability[c["name"]] + exec_time

            if ect < min_ect:
                min_ect = ect
                best_container = c

        # Update waktu tersedia container terpilih
        exec_time = (task["length"] / best_container["speed"]) * MI_TO_SECONDS_BASE
        availability[best_container["name"]] += exec_time
        assignments.append((task, best_container))

    return assignments


# ── Task Execution ────────────────────────────────────────────────────────────

def execute_task(task: dict, container: dict, results: list, lock: threading.Lock):
    """Menjalankan satu task di dalam container Docker."""
    duration_secs = (task["length"] / container["speed"]) * MI_TO_SECONDS_BASE
    duration_secs = max(0.1, duration_secs)  # minimal 0.1 detik

    # Skrip Python komputasi intensif yang berjalan di dalam container
    # Menggunakan iterasi matematis untuk membebani CPU secara nyata
    cpu_script = f"""
import time, math
start = time.time()
target = {duration_secs:.4f}
x = 0.0
while time.time() - start < target:
    x += math.sqrt(abs(math.sin(x + 1.0)))
elapsed = time.time() - start
print(f"Task {task['id']} selesai: {{elapsed:.3f}}s")
"""

    cmd = [
        "docker", "exec", container["name"],
        "python3", "-c", cpu_script
    ]

    t_start = time.time()
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=duration_secs * 3 + 10)
        elapsed = time.time() - t_start
        success = proc.returncode == 0
        output = proc.stdout.strip() or proc.stderr.strip()
    except subprocess.TimeoutExpired:
        elapsed = time.time() - t_start
        success = False
        output = "TIMEOUT"
    except Exception as e:
        elapsed = time.time() - t_start
        success = False
        output = str(e)

    record = {
        "task_id":          task["id"],
        "length_mi":        task["length"],
        "container":        container["name"],
        "expected_exec_s":  round(duration_secs, 4),
        "actual_exec_s":    round(elapsed, 4),
        "success":          success,
        "output":           output,
        "timestamp":        datetime.now().isoformat(),
    }

    with lock:
        results.append(record)
        status = "✓" if success else "✗"
        print(f"  {status} Task {task['id']:3d} ({task['length']:>8,} MI) → "
              f"{container['name']:<20} [{elapsed:.2f}s / expected {duration_secs:.2f}s]")


# ── Result Reporting ──────────────────────────────────────────────────────────

def compute_metrics(results: list[dict], makespan: float, assignments: list) -> dict:
    """Menghitung 5 metrik utama dari hasil eksekusi real-world."""
    if not results:
        return {}

    successful = [r for r in results if r["success"]]
    n = len(results)
    n_ok = len(successful)

    # Beban per container
    container_loads = {c["name"]: 0.0 for c in CONTAINERS}
    for task, container in assignments:
        exec_t = (task["length"] / container["speed"]) * MI_TO_SECONDS_BASE
        container_loads[container["name"]] += exec_t

    loads = list(container_loads.values())
    t_max = max(loads)
    t_min = min(loads)
    t_avg = sum(loads) / len(loads) if loads else 1

    di = (t_max - t_min) / t_avg if t_avg > 0 else 0
    throughput = n_ok / makespan if makespan > 0 else 0

    return {
        "total_tasks":        n,
        "successful_tasks":   n_ok,
        "makespan_s":         round(makespan, 4),
        "throughput_task_s":  round(throughput, 6),
        "degree_of_imbalance": round(di, 6),
        "container_loads":    {k: round(v, 4) for k, v in container_loads.items()},
    }


def save_results(results: list[dict], path: str):
    """Menyimpan hasil eksekusi ke CSV."""
    if not results:
        return
    os.makedirs(os.path.dirname(path) if os.path.dirname(path) else ".", exist_ok=True)
    fieldnames = results[0].keys()
    with open(path, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(results)
    print(f"\n  ✓ Hasil disimpan ke: {path}")


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    print()
    print("╔══════════════════════════════════════════════════════════════════════╗")
    print("║      LJFP Real-World Scheduler — Kelompok 6                         ║")
    print("║      Longest Job to Fastest Processor (Docker Edition)              ║")
    print("╚══════════════════════════════════════════════════════════════════════╝")
    print()

    # Cek apakah Docker tersedia
    try:
        subprocess.run(["docker", "ps"], capture_output=True, check=True)
    except (subprocess.CalledProcessError, FileNotFoundError):
        print("  ✗ Docker tidak tersedia atau tidak berjalan.")
        print("    Pastikan Docker Desktop aktif dan WSL integration diaktifkan.")
        print("    Lalu jalankan: docker compose up -d (di folder realworld/)")
        return

    # Cek dataset
    if not os.path.exists(DATASET_PATH):
        # Coba cari di parent
        alt = "../dataset/synthetic_500tasks.csv"
        if os.path.exists(alt):
            import shutil
            os.makedirs("workload", exist_ok=True)
            shutil.copy(alt, DATASET_PATH)
            print(f"  Dataset disalin dari {alt} ke {DATASET_PATH}")
        else:
            print(f"  ✗ Dataset tidak ditemukan: {DATASET_PATH}")
            print("    Jalankan python3 generate_dataset.py di folder root proyek terlebih dahulu.")
            return

    # 1. Load & sort tasks (LJFP Step 1)
    print("  [1/4] Memuat dan menyortir dataset (LJFP: Longest Job First)...")
    tasks = load_tasks(DATASET_PATH, MAX_REAL_TASKS)

    # 2. LJFP assignment
    print("\n  [2/4] Menjalankan LJFP scheduling assignment...")
    assignments = ljfp_assign(tasks)

    print("\n  Rencana Assignment LJFP:")
    print(f"  {'Task ID':>8} | {'Length (MI)':>12} | Container")
    print("  " + "-" * 42)
    for task, container in assignments[:10]:
        print(f"  {task['id']:>8} | {task['length']:>12,} | {container['name']}")
    if len(assignments) > 10:
        print(f"  ... dan {len(assignments)-10} task lainnya")

    # 3. Eksekusi paralel
    print(f"\n  [3/4] Mengeksekusi {len(assignments)} task secara paralel di container...")
    results = []
    lock = threading.Lock()
    threads = []

    t_global_start = time.time()
    for task, container in assignments:
        t = threading.Thread(
            target=execute_task,
            args=(task, container, results, lock)
        )
        t.start()
        threads.append(t)

    for t in threads:
        t.join()

    makespan = time.time() - t_global_start

    # 4. Hitung metrik & simpan
    print("\n  [4/4] Menghitung metrik dan menyimpan hasil...")
    metrics = compute_metrics(results, makespan, assignments)
    save_results(results, RESULTS_PATH)

    # Tampilkan ringkasan
    print()
    print("  ╔═══════════════════════════════════════════════════════╗")
    print("  ║  HASIL EKSPERIMEN REAL-WORLD — LJFP                   ║")
    print("  ╠═══════════════════════════════════════════════════════╣")
    print(f"  ║  Total Task Dieksekusi : {metrics.get('total_tasks', 0):<30}║")
    print(f"  ║  Task Berhasil         : {metrics.get('successful_tasks', 0):<30}║")
    print(f"  ║  ① Makespan            : {metrics.get('makespan_s', 0):<26.4f} s║")
    print(f"  ║  ③ Degree of Imbalance : {metrics.get('degree_of_imbalance', 0):<30.6f}║")
    print(f"  ║  ⑤ Throughput          : {metrics.get('throughput_task_s', 0):<24.6f} t/s║")
    print("  ╠═══════════════════════════════════════════════════════╣")
    print("  ║  Beban per Container:                                  ║")
    for name, load in metrics.get("container_loads", {}).items():
        print(f"  ║    {name:<24} : {load:<22.4f} s║")
    print("  ╚═══════════════════════════════════════════════════════╝")
    print()
    print("  → Buka http://localhost:3000 untuk melihat grafik Grafana")
    print("    (login: admin / admin)")


if __name__ == "__main__":
    main()
