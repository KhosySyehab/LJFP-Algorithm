#!/usr/bin/env python3
"""
generate_dataset.py — Pembangkit Dataset Sintetis Kelompok 6 (LJFP)

Membangkitkan 500 task dengan distribusi Weibull (skewed/right-heavy) untuk
menguji keunggulan algoritma LJFP pada workload sangat heterogen.

Karakteristik dataset:
  - Total task  : 500
  - Distribusi  : Weibull shape=0.8 (sangat positif-skewed)
  - Rentang MI  : 1.000 – 900.000 MI (ketimpangan ekstrem)
  - PE req.     : 1 (semua task independen, sesuai constraint draft)
  - Submisi     : semua task tiba sekaligus (batch processing, delay=0)

Mengapa Weibull?
  Distribusi Weibull dengan shape < 1 menghasilkan "ekor panjang" — banyak
  task kecil (≈80%) dan sedikit task raksasa (≈20%). Ini adalah kondisi ideal
  untuk menguji LJFP: task raksasa itu yang perlu dipasangkan ke VM-Fastest.
"""

import numpy as np
import pandas as pd
import os

def generate_synthetic(n: int = 500, seed: int = 42) -> pd.DataFrame:
    rng = np.random.default_rng(seed)

    # Weibull distribution: shape=0.8 → sangat skewed (mayoritas kecil, sedikit raksasa)
    raw = rng.weibull(0.8, n)

    # Scale ke rentang 1_000 – 900_000 MI
    raw_min, raw_max = raw.min(), raw.max()
    lengths = ((raw - raw_min) / (raw_max - raw_min) * (900_000 - 1_000) + 1_000).astype(int)
    lengths = np.clip(lengths, 1_000, 900_000)

    df = pd.DataFrame({
        "Task_ID":              range(1, n + 1),
        "Length_MI":            lengths,
        "PE_Requirement":       1,                               # semua independen
        "File_Input_Size_KB":   rng.integers(100, 5001, size=n), # 100–5000 KB
        "File_Output_Size_KB":  rng.integers(50,  2001, size=n), # 50–2000 KB
    })

    # Acak urutan (batch processing — tidak ada urutan bermakna di input)
    df = df.sample(frac=1, random_state=seed).reset_index(drop=True)
    df["Task_ID"] = range(1, n + 1)  # re-index setelah shuffle

    return df


def print_stats(df: pd.DataFrame):
    lengths = df["Length_MI"]
    print(f"\n  Statistik Dataset Sintetis (N={len(df)}):")
    print(f"  {'Min':<12}: {lengths.min():>10,} MI")
    print(f"  {'Max':<12}: {lengths.max():>10,} MI")
    print(f"  {'Mean':<12}: {lengths.mean():>10,.0f} MI")
    print(f"  {'Median':<12}: {lengths.median():>10,.0f} MI")
    print(f"  {'Std Dev':<12}: {lengths.std():>10,.0f} MI")

    # Distribusi kelas task
    small  = (lengths < 10_000).sum()
    medium = ((lengths >= 10_000) & (lengths < 100_000)).sum()
    large  = (lengths >= 100_000).sum()
    print(f"\n  Distribusi Kelas:")
    print(f"  {'Kecil  (<10K MI)':<25}: {small:>5} task ({small/len(df)*100:.1f}%)")
    print(f"  {'Sedang (10K–100K MI)':<25}: {medium:>5} task ({medium/len(df)*100:.1f}%)")
    print(f"  {'Besar  (≥100K MI)':<25}: {large:>5} task ({large/len(df)*100:.1f}%)")


if __name__ == "__main__":
    os.makedirs("dataset", exist_ok=True)
    output_path = "dataset/synthetic_500tasks.csv"

    print("=" * 60)
    print("  Dataset Generator — Kelompok 6 (LJFP)")
    print("  Distribusi: Weibull shape=0.8 (highly skewed)")
    print("=" * 60)

    df = generate_synthetic(n=500, seed=42)
    df.to_csv(output_path, index=False)

    print_stats(df)
    print(f"\n  ✓ Dataset berhasil disimpan ke: {output_path}")
    print(f"  ✓ Preview 5 baris pertama:")
    print(df.head().to_string(index=False))
