package com.kelompok6;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.vms.Vm;

import java.util.*;

/**
 * Implementasi algoritma heuristik LJFP (Longest Job to Fastest Processor).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Prinsip Kerja LJFP
 * ─────────────────────────────────────────────────────────────────────────────
 * LJFP adalah algoritma penjadwalan berbasis pengurutan (list scheduling) yang
 * bekerja pada lingkungan heterogen. Ide utamanya:
 *
 *   "Pasangkan task terpanjang dengan prosesor tercepat, sehingga tugas berat
 *    tidak terjebak di node lambat dan menyebabkan bottleneck pada makespan."
 *
 * Langkah Algoritma:
 *   1. Urutkan semua task DESCENDING berdasarkan Length (MI) — task terpanjang
 *      didahulukan (Longest Job First principle).
 *
 *   2. Untuk setiap task (dari terpanjang ke terpendek):
 *      a. Hitung Estimated Completion Time (ECT) pada setiap VM kandidat:
 *           ECT(task_i, vm_j) = max(submissionDelay_i, ready_j) + (Length_i / MIPS_j)
 *         di mana ready_j adalah waktu VM j selesai mengeksekusi task sebelumnya.
 *      b. Pilih VM dengan ECT terkecil → assignment paling efisien.
 *      c. Update waktu tersedia VM yang dipilih.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Perbedaan dengan PEFT dan MCT
 * ─────────────────────────────────────────────────────────────────────────────
 *  - PEFT  : Hitung EFT per-PE (multi-core aware), tanpa sorting pre-pass.
 *  - MCT   : Sama-sama greedy min-completion-time, TAPI tanpa sorting.
 *             Task dialokasikan dalam urutan kedatangan (FCFS order), bukan
 *             sorted by length. Inilah perbedaan fundamental LJFP vs MCT.
 *  - LJFP  : Sort DESCENDING by length → greedy min-ECT. Sorting inilah yang
 *             membuat LJFP optimal pada lingkungan heterogen dengan beban
 *             sangat timpang (highly skewed workload).
 *
 * Kompleksitas: O(N log N) untuk sorting + O(N × M) untuk assignment.
 *               N = jumlah task, M = jumlah VM.
 */
public class LjfpBroker extends DatacenterBrokerSimple {

    public LjfpBroker(final CloudSimPlus simulation) {
        super(simulation);
    }

    @Override
    protected void requestDatacentersToCreateWaitingCloudlets() {
        final List<Cloudlet> waitingList = getCloudletWaitingList();
        final List<Vm>       vmList      = getVmExecList();

        if (waitingList.isEmpty() || vmList.isEmpty()) {
            super.requestDatacentersToCreateWaitingCloudlets();
            return;
        }

        // ── Step 1: LJFP Core — Sort tasks DESCENDING by Length (MI) ─────────
        // Ini adalah pembeda utama LJFP: task terpanjang harus dikerjakan lebih
        // dulu agar tidak menjadi bottleneck di akhir eksekusi.
        waitingList.sort(Comparator.comparingLong(Cloudlet::getLength).reversed());

        // ── Step 2: Inisialisasi waktu tersedia setiap VM ─────────────────────
        // vmReadyTime[vmId] = waktu paling awal VM tersebut bisa menerima task baru
        Map<Long, Double> vmReadyTime = new HashMap<>();
        for (Vm vm : vmList) {
            vmReadyTime.put(vm.getId(), 0.0);
        }

        // ── Step 3: Greedy assignment — longest task → VM with min ECT ────────
        for (Cloudlet cloudlet : waitingList) {
            long   taskLength   = cloudlet.getLength();
            double arrivalTime  = cloudlet.getSubmissionDelay();

            Vm     bestVm       = null;
            double minEct       = Double.MAX_VALUE;
            double bestFinish   = 0.0;

            for (Vm vm : vmList) {
                // Saat paling awal task bisa mulai di VM ini
                double startTime  = Math.max(arrivalTime, vmReadyTime.get(vm.getId()));
                // Perkiraan waktu eksekusi (EET = Length / MIPS)
                double execTime   = (double) taskLength / vm.getMips();
                // Estimated Completion Time
                double ect        = startTime + execTime;

                if (ect < minEct) {
                    minEct      = ect;
                    bestFinish  = ect;
                    bestVm      = vm;
                }
                // Tie-breaker: jika ECT sama, pilih VM dengan MIPS lebih tinggi
                // (mengutamakan prosesor cepat sesuai filosofi LJFP)
                else if (Math.abs(ect - minEct) < 1e-9 && bestVm != null
                         && vm.getMips() > bestVm.getMips()) {
                    minEct      = ect;
                    bestFinish  = ect;
                    bestVm      = vm;
                }
            }

            if (bestVm != null) {
                cloudlet.setVm(bestVm);
                // Update waktu tersedia VM yang dipilih
                vmReadyTime.put(bestVm.getId(), bestFinish);
            }
        }

        // ── Step 4: Kirim cloudlet ke datacenter ──────────────────────────────
        super.requestDatacentersToCreateWaitingCloudlets();
    }
}
