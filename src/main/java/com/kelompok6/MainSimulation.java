package com.kelompok6;

import org.cloudsimplus.brokers.DatacenterBroker;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.vms.Vm;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Entry point simulasi — Kelompok 6 (LJFP vs MCT vs FCFS).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Skenario Pengujian:
 * ─────────────────────────────────────────────────────────────────────────────
 *   Skenario 1 : Dataset Sintetis 500 Task (distribusi Weibull/skewed)
 *                → Dirancang khusus untuk menguji keunggulan LJFP
 *                  karena distribusi sangat timpang (1.000 – 900.000 MI)
 *
 *   Skenario 2 : Dataset GoCJ 500 Task (Google Cloud Jobs)
 *                → Dataset nyata dari lingkungan cloud Google
 *                  Gunakan jika file dataset/gocj_500tasks.csv tersedia
 *
 * Algoritma yang dibandingkan:
 *   - LJFP (Longest Job to Fastest Processor) — Diusulkan
 *   - MCT  (Minimum Completion Time) ────────── Baseline 1
 *   - FCFS (First Come First Served) ────────── Baseline 2
 *
 * Cara menjalankan:
 *   ./gradlew runAll   → semua algoritma, semua dataset
 *   ./gradlew runLJFP  → hanya LJFP, semua dataset
 *   ./gradlew runMCT   → hanya MCT, semua dataset
 *   ./gradlew runFCFS  → hanya FCFS, semua dataset
 */
public class MainSimulation {

    // Ukuran dataset yang diuji agar tidak hanya 500 task)
    private static final int[]  DATASET_SIZES = {500, 600, 700, 800, 900, 1000};
    private static final String RESULTS_DIR   = "results";

    public static void main(String[] args) throws Exception {
        printBanner();

        String mode = args.length > 0 ? args[0].toUpperCase() : "ALL";
        Files.createDirectories(Path.of(RESULTS_DIR));

        List<MetricsCollector.ExperimentResult> allResults = new ArrayList<>();

        // ═════════════════════════════════════════════════════════════════════
        // SKENARIO 1: DATASET SINTETIS (Weibull) — SKALA BERTAHAP 500–1000 Task
        // ═════════════════════════════════════════════════════════════════════
        List<MetricsCollector.ExperimentResult> syntheticAll = new ArrayList<>();
        for (int size : DATASET_SIZES) {
            String syntheticCsv = "dataset/synthetic_" + size + "tasks.csv";
            System.out.println("\n" + "═".repeat(72));
            System.out.println("  SKENARIO 1 : DATASET SINTETIS (" + size + " TASK — DISTRIBUSI WEIBULL)");
            System.out.println("═".repeat(72));

            if (Files.exists(Path.of(syntheticCsv))) {
                List<MetricsCollector.ExperimentResult> s1 = runScenario(mode, syntheticCsv, "Sintetis-" + size);
                printComparisonIfFull(s1);
                if (!s1.isEmpty()) {
                    syntheticAll.addAll(s1);
                    allResults.addAll(s1);
                }
            } else {
                System.out.println("\n  ⚠  File dataset tidak ditemukan: " + syntheticCsv);
                System.out.println("     Jalankan terlebih dahulu: python3 generate_dataset.py --n " + size);
            }
        }
        if (!syntheticAll.isEmpty()) {
            MetricsCollector.exportToCsv(syntheticAll, RESULTS_DIR + "/synthetic_results.csv");
        }

        // ═════════════════════════════════════════════════════════════════════
        // SKENARIO 2: DATASET GoCJ — SKALA BERTAHAP 500–1000 Task
        // ═════════════════════════════════════════════════════════════════════
        List<MetricsCollector.ExperimentResult> gocjAll = new ArrayList<>();
        for (int size : DATASET_SIZES) {
            String gocjCsv = "dataset/gocj_" + size + "tasks.csv";
            System.out.println("\n\n" + "═".repeat(72));
            System.out.println("  SKENARIO 2 : DATASET GoCJ (" + size + " TASK — GOOGLE CLOUD JOBS)");
            System.out.println("═".repeat(72));

            if (Files.exists(Path.of(gocjCsv))) {
                List<MetricsCollector.ExperimentResult> s2 = runScenario(mode, gocjCsv, "GoCJ-" + size);
                printComparisonIfFull(s2);
                if (!s2.isEmpty()) {
                    gocjAll.addAll(s2);
                    allResults.addAll(s2);
                }
            } else {
                System.out.println("\n  ⚠  File dataset tidak ditemukan: " + gocjCsv);
                System.out.println("     Jalankan: python3 preprocess_gocj.py --input dataset/GoCJ_Dataset_" + size + ".txt");
            }
        }
        if (!gocjAll.isEmpty()) {
            MetricsCollector.exportToCsv(gocjAll, RESULTS_DIR + "/gocj_results.csv");
        }

        // Simpan semua hasil gabungan
        if (!allResults.isEmpty()) {
            MetricsCollector.exportToCsv(allResults, RESULTS_DIR + "/all_results.csv");
        }

        printFooter();
    }

    // ── Core experiment runner ────────────────────────────────────────────────

    private static List<MetricsCollector.ExperimentResult> runScenario(
            String mode, String csvPath, String datasetLabel) throws Exception {

        List<MetricsCollector.ExperimentResult> results = new ArrayList<>();

        if (mode.equals("ALL") || mode.equals("LJFP")) {
            results.add(runExperiment("LJFP", datasetLabel, csvPath));
        }
        if (mode.equals("ALL") || mode.equals("MCT")) {
            results.add(runExperiment("MCT", datasetLabel, csvPath));
        }
        if (mode.equals("ALL") || mode.equals("FCFS")) {
            results.add(runExperiment("FCFS", datasetLabel, csvPath));
        }
        return results;
    }

    private static MetricsCollector.ExperimentResult runExperiment(
            String algorithm, String datasetLabel, String csvPath) throws Exception {

        System.out.printf("%n  ┌─────────────────────────────────────────────────────────┐%n");
        System.out.printf("  │  Algoritma : %-44s│%n", fullName(algorithm));
        System.out.printf("  │  Dataset   : %-44s│%n", datasetLabel);
        System.out.printf("  └─────────────────────────────────────────────────────────┘%n%n");

        // 1. Inisialisasi simulasi CloudSim Plus
        CloudSimPlus simulation = new CloudSimPlus();

        // 2. Bangun datacenter (3 Host + 20 VM sesuai draft Kelompok 6)
        System.out.println("  [1/4] Membangun arsitektur datacenter...");
        DatacenterBuilder.DatacenterResult dc = DatacenterBuilder.build(simulation);

        // 3. Buat broker sesuai algoritma
        System.out.println("  [2/4] Menginisialisasi broker (" + fullName(algorithm) + ")...");
        DatacenterBroker broker = createBroker(algorithm, simulation);

        // 4. Muat dataset
        System.out.println("  [3/4] Memuat dataset " + datasetLabel + "...");
        List<Cloudlet> cloudlets = DatasetLoader.loadFromCsv(csvPath, 0); // 0 = muat SEMUA baris di CSV, tidak di-cap 500

        // 5. Submit VM dan cloudlet ke broker
        broker.submitVmList(dc.vmList());
        broker.submitCloudletList(cloudlets);

        // 6. Jalankan simulasi
        System.out.println("  [4/4] Menjalankan simulasi CloudSim Plus...");
        long wallStart = System.currentTimeMillis();
        simulation.start();
        long wallElapsed = System.currentTimeMillis() - wallStart;

        // 7. Kumpulkan metrik
        List<Cloudlet> finished = broker.getCloudletFinishedList();
        List<Host>     hosts    = dc.datacenter().getHostList();

        String algoLabel = fullName(algorithm) + " [" + datasetLabel + "]";
        MetricsCollector.ExperimentResult result = MetricsCollector.collectMetrics(
                algoLabel, finished, dc.vmList(), hosts, simulation.clock());
        result.dataset = datasetLabel;

        System.out.printf("  ✓ Simulasi selesai dalam %d ms (wall clock).%n", wallElapsed);
        MetricsCollector.printReport(result);
        MetricsCollector.printCloudletSample(finished, algorithm, 10);

        return result;
    }

    private static DatacenterBroker createBroker(String algorithm, CloudSimPlus sim) {
        return switch (algorithm.toUpperCase()) {
            case "LJFP" -> new LjfpBroker(sim);
            case "MCT"  -> new MctBroker(sim);
            case "FCFS" -> new FcfsBroker(sim);
            default -> {
                System.out.println("  Algoritma tidak dikenal: " + algorithm + " — menggunakan LJFP.");
                yield new LjfpBroker(sim);
            }
        };
    }

    private static String fullName(String algo) {
        return switch (algo.toUpperCase()) {
            case "LJFP" -> "LJFP (Longest Job to Fastest Processor)";
            case "MCT"  -> "MCT  (Minimum Completion Time)";
            case "FCFS" -> "FCFS (First Come First Served)";
            default     -> algo;
        };
    }

    private static void printComparisonIfFull(List<MetricsCollector.ExperimentResult> r) {
        if (r.size() >= 2) {
            MetricsCollector.printComparisonTable(r);
        }
    }

    // ── Banner / Footer ───────────────────────────────────────────────────────

    private static void printBanner() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════╗");
        System.out.println("║      OPTIMASI PENJADWALAN TASK PADA KOMPUTASI AWAN                   ║");
        System.out.println("║      Algoritma: LJFP (Longest Job to Fastest Processor)              ║");
        System.out.println("║                                                                      ║");
        System.out.println("║  Kelompok 6 — Strategi Optimasi Komputasi Awan (SOKA)                ║");
        System.out.println("║  Departemen Teknologi Informasi, Institut Teknologi Sepuluh Nopember ║");
        System.out.println("║                                                                      ║");
        System.out.println("║  Simulator  : CloudSim Plus 8.0.0                                   ║");
        System.out.println("║  Baseline   : MCT (Minimum Completion Time) + FCFS                  ║");
        System.out.println("║  Dataset    : Sintetis Weibull (500 task) + GoCJ (500 task)          ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════╝");
    }

    private static void printFooter() {
        System.out.println("\n");
        System.out.println("╔══════════════════════════════════════════════════════════════════════╗");
        System.out.println("║  ✓ Seluruh eksperimen simulasi berhasil diselesaikan!                ║");
        System.out.println("║  ✓ Hasil disimpan di folder results/ dalam format CSV.               ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════╝");
    }
}
