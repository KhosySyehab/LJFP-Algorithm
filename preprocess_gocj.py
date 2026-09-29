#!/usr/bin/env python3
"""
preprocess_gocj.py — Preprocessor Dataset GoCJ untuk Kelompok 6 (LJFP)

Dataset GoCJ (Google Cloud Jobs) dari Mendeley:
  https://data.mendeley.com/datasets/b7bp6xhrcd/1

Langkah penggunaan:
  1. Download dataset dari link di atas
  2. Ekstrak dan temukan file CSV yang berisi job data
  3. Letakkan file CSV di folder dataset/
  4. Jalankan: python3 preprocess_gocj.py --input dataset/<nama_file>.csv

Jika file GoCJ tidak tersedia, script ini akan membangkitkan
dataset sintetis alternatif dengan distribusi berbeda (log-normal)
sebagai pengganti untuk Skenario 2.
"""

import numpy as np
import pandas as pd
import argparse
import os
import sys

OUTPUT_PATH = "dataset/gocj_500tasks.csv"
N_TASKS = 500


def preprocess_gocj(input_path: str) -> pd.DataFrame:
    """
    Membaca file GoCJ (.txt atau .csv) dan mengekstrak kolom Length_MI.
    File GoCJ (misal GoCJ_Dataset_500.txt) berisi nilai ukuran job per baris dalam satuan MI.
    """
    print(f"  Membaca dataset GoCJ: {input_path}")
    
    # Coba baca dengan header default
    try:
        df = pd.read_csv(input_path, sep=r'[\s,]+', engine='python')
    except Exception:
        df = pd.read_csv(input_path)

    length_col = find_column(df, ["job_length", "length", "mi", "cpu_time", "runtime", "run_time"])
    
    if length_col is not None:
        print(f"  Menggunakan kolom: '{length_col}' sebagai Length_MI")
        lengths = pd.to_numeric(df[length_col], errors='coerce').dropna()
    else:
        # Jika tidak ada header (format standar GoCJ_Dataset_*.txt dari Mendeley)
        print("  Format terdeteksi: Raw data tanpa header (GoCJ .txt format standar)")
        df_raw = pd.read_csv(input_path, sep=r'[\s,]+', engine='python', header=None)
        # Ambil kolom pertama sebagai Length (MI)
        lengths = pd.to_numeric(df_raw.iloc[:, 0], errors='coerce').dropna()

    lengths = lengths[lengths > 0].astype(int)

    if len(lengths) == 0:
        raise ValueError(f"Tidak ada data numerik valid ditemukan di {input_path}")

    # Ambil sample N_TASKS jika lebih banyak
    if len(lengths) > N_TASKS:
        lengths = lengths.sample(n=N_TASKS, random_state=42)
    lengths = lengths.reset_index(drop=True)

    rng = np.random.default_rng(42)
    result = pd.DataFrame({
        "Task_ID":              range(1, len(lengths) + 1),
        "Length_MI":            lengths.values,
        "PE_Requirement":       1,
        "File_Input_Size_KB":   rng.integers(100, 5001, size=len(lengths)),
        "File_Output_Size_KB":  rng.integers(50,  2001, size=len(lengths)),
    })
    return result


def generate_lognormal_alternative(n: int = 500, seed: int = 99) -> pd.DataFrame:
    """
    Membangkitkan dataset alternatif dengan distribusi log-normal
    (distribusi berbeda dari dataset sintetis Weibull di Skenario 1).
    Ini memastikan kedua skenario menguji karakteristik workload yang berbeda.
    """
    rng = np.random.default_rng(seed)

    # Log-normal: mu=10, sigma=2 → rentang luas, mirip distribusi cloud job nyata
    lengths = rng.lognormal(mean=10.0, sigma=2.0, size=n).astype(int)
    lengths = np.clip(lengths, 1_000, 900_000)

    df = pd.DataFrame({
        "Task_ID":              range(1, n + 1),
        "Length_MI":            lengths,
        "PE_Requirement":       1,
        "File_Input_Size_KB":   rng.integers(100, 5001, size=n),
        "File_Output_Size_KB":  rng.integers(50,  2001, size=n),
    })
    df = df.sample(frac=1, random_state=seed).reset_index(drop=True)
    df["Task_ID"] = range(1, n + 1)
    return df


def find_column(df: pd.DataFrame, candidates: list[str]) -> str | None:
    """Mencari nama kolom secara case-insensitive."""
    lower_cols = {c.lower(): c for c in df.columns}
    for candidate in candidates:
        if candidate.lower() in lower_cols:
            return lower_cols[candidate.lower()]
    return None


def print_stats(df: pd.DataFrame, label: str = "Dataset"):
    lengths = df["Length_MI"]
    print(f"\n  Statistik {label} (N={len(df)}):")
    print(f"  {'Min':<12}: {lengths.min():>10,} MI")
    print(f"  {'Max':<12}: {lengths.max():>10,} MI")
    print(f"  {'Mean':<12}: {lengths.mean():>10,.0f} MI")
    print(f"  {'Median':<12}: {lengths.median():>10,.0f} MI")
    print(f"  {'Std Dev':<12}: {lengths.std():>10,.0f} MI")


def main():
    parser = argparse.ArgumentParser(
        description="Preprocess GoCJ dataset atau bangkitkan alternatif log-normal"
    )
    parser.add_argument("--input", type=str, default=None,
                        help="Path ke file GoCJ CSV asli (opsional)")
    args = parser.parse_args()

    os.makedirs("dataset", exist_ok=True)

    print("=" * 60)
    print("  GoCJ Dataset Preprocessor — Kelompok 6 (LJFP)")
    print("=" * 60)

    if args.input and os.path.exists(args.input):
        # Gunakan dataset GoCJ asli
        try:
            df = preprocess_gocj(args.input)
            label = "GoCJ (Real)"
        except Exception as e:
            print(f"\n  ⚠  Gagal memproses GoCJ: {e}")
            print("     Membangkitkan dataset log-normal sebagai pengganti...")
            df = generate_lognormal_alternative(N_TASKS)
            label = "Log-Normal Alternative"
    else:
        if args.input:
            print(f"  ⚠  File tidak ditemukan: {args.input}")
        print("  → Membangkitkan dataset log-normal sebagai Skenario 2...")
        df = generate_lognormal_alternative(N_TASKS)
        label = "Log-Normal Alternative (GoCJ proxy)"

    print_stats(df, label)
    df.to_csv(OUTPUT_PATH, index=False)
    print(f"\n  ✓ Dataset berhasil disimpan ke: {OUTPUT_PATH}")
    print(f"  ✓ Preview 5 baris pertama:")
    print(df.head().to_string(index=False))


if __name__ == "__main__":
    main()
