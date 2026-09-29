package com.kelompok6;

import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Loader dataset untuk Kelompok 6 — mendukung:
 *   1. Dataset Sintetis CSV (dibangkitkan oleh generate_dataset.py)
 *   2. Dataset GoCJ CSV (dari Mendeley, sudah dipreproses)
 *
 * Format CSV yang diharapkan (header wajib ada):
 *   Task_ID, Length_MI, PE_Requirement, File_Input_Size_KB, File_Output_Size_KB
 *
 * Contoh baris:
 *   1, 245000, 1, 1024, 512
 *   2, 12000,  1, 300,  150
 */
public class DatasetLoader {

    /** Default ukuran file jika kolom tidak tersedia */
    private static final long DEFAULT_FILE_SIZE_BYTES   = 300;
    private static final long DEFAULT_OUTPUT_SIZE_BYTES  = 300;

    /**
     * Membaca dataset dari file CSV dan mengonversinya ke List of Cloudlet.
     *
     * @param csvPath  path ke file CSV
     * @param maxTasks jumlah maksimum task yang diambil (0 = semua)
     * @return List of Cloudlet siap disubmit ke broker
     */
    public static List<Cloudlet> loadFromCsv(String csvPath, int maxTasks) throws IOException {
        List<Cloudlet> cloudlets = new ArrayList<>();
        List<String> lines = Files.readAllLines(Path.of(csvPath));

        if (lines.isEmpty()) {
            throw new IOException("Dataset kosong: " + csvPath);
        }

        // Parse header untuk menentukan indeks kolom secara fleksibel
        String[] headers = lines.get(0).trim().split(",");
        Map<String, Integer> colIndex = new LinkedHashMap<>();
        for (int i = 0; i < headers.length; i++) {
            colIndex.put(headers[i].trim().toLowerCase().replace(" ", "_"), i);
        }

        // Kolom wajib
        int idxLength = getColIndex(colIndex, "length_mi", "length", "length(mi)", "mi");
        int idxPe     = getColIndex(colIndex, "pe_requirement", "pe", "pes", "num_pe");
        int idxInput  = getOptionalColIndex(colIndex, "file_input_size_kb", "input_size_kb", "input_kb");
        int idxOutput = getOptionalColIndex(colIndex, "file_output_size_kb", "output_size_kb", "output_kb");

        int taskId = 0;
        for (int lineNum = 1; lineNum < lines.size(); lineNum++) {
            String line = lines.get(lineNum).trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;

            String[] fields = line.split(",");
            if (fields.length <= Math.max(idxLength, idxPe)) continue;

            try {
                long lengthMI   = parseLong(fields[idxLength]);
                int  pesNeeded  = (int) parseLong(fields[idxPe]);

                // Validasi
                if (lengthMI <= 0) lengthMI = 1;
                if (pesNeeded <= 0) pesNeeded = 1;
                if (pesNeeded > 4) pesNeeded = 4; // batasi ke maks PE VM-Fastest

                // Ukuran file (KB → bytes)
                long inputSize  = idxInput  >= 0 ? parseLong(fields[idxInput])  * 1024 : DEFAULT_FILE_SIZE_BYTES;
                long outputSize = idxOutput >= 0 ? parseLong(fields[idxOutput]) * 1024 : DEFAULT_OUTPUT_SIZE_BYTES;
                if (inputSize  <= 0) inputSize  = DEFAULT_FILE_SIZE_BYTES;
                if (outputSize <= 0) outputSize = DEFAULT_OUTPUT_SIZE_BYTES;

                Cloudlet cloudlet = new CloudletSimple(lengthMI, pesNeeded)
                        .setFileSize(inputSize)
                        .setOutputSize(outputSize)
                        .setUtilizationModelCpu(new UtilizationModelFull())
                        .setUtilizationModelRam(new UtilizationModelDynamic(0.05))
                        .setUtilizationModelBw(new UtilizationModelDynamic(0.02));

                // Batch processing: semua task tiba sekaligus (submissionDelay = 0)
                cloudlets.add(cloudlet);
                taskId++;

                if (maxTasks > 0 && taskId >= maxTasks) break;

            } catch (NumberFormatException ignored) {
                // Skip baris dengan format tidak valid
            }
        }

        System.out.printf("  [Dataset] Berhasil memuat %d task dari: %s%n", cloudlets.size(), csvPath);
        if (!cloudlets.isEmpty()) {
            long minLen = cloudlets.stream().mapToLong(Cloudlet::getLength).min().orElse(0);
            long maxLen = cloudlets.stream().mapToLong(Cloudlet::getLength).max().orElse(0);
            double avgLen = cloudlets.stream().mapToLong(Cloudlet::getLength).average().orElse(0);
            System.out.printf("  [Dataset] Rentang Length: %,d – %,d MI | Rata-rata: %,.0f MI%n",
                    minLen, maxLen, avgLen);
        }

        return cloudlets;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Mencari indeks kolom wajib; return 1 sebagai fallback jika tidak ditemukan. */
    private static int getColIndex(Map<String, Integer> map, String... candidates) {
        for (String key : candidates) {
            if (map.containsKey(key)) return map.get(key);
        }
        return 1; // default fallback ke kolom ke-2
    }

    /** Mencari indeks kolom opsional; return -1 jika tidak ada kolom yang cocok. */
    private static int getOptionalColIndex(Map<String, Integer> map, String... candidates) {
        for (String key : candidates) {
            if (map.containsKey(key)) return map.get(key);
        }
        return -1; // tidak ditemukan, akan menggunakan nilai default
    }

    private static long parseLong(String s) {
        return Long.parseLong(s.trim().replace("\"", "").replace("'", ""));
    }
}
