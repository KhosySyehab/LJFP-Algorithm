package com.kelompok6;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.vms.Vm;

import java.util.*;

/**
 * Baseline 1: MCT (Minimum Completion Time).
 *
 * MCT mengalokasikan setiap task ke VM yang menghasilkan Estimated Completion
 * Time (ECT) terkecil — TANPA melakukan pre-sorting berdasarkan panjang task.
 *
 * Perbedaan fundamental dengan LJFP:
 *  - MCT  : task diproses dalam urutan kedatangan (FIFO-ish), kemudian
 *            dipasangkan ke VM dengan ECT terkecil.
 *  - LJFP : task DIURUTKAN dulu (terpanjang lebih dulu), BARU dipasangkan
 *            ke VM dengan ECT terkecil.
 *
 * Ini menjadikan MCT sebagai baseline yang adil: keduanya menggunakan
 * strategi greedy min-ECT, namun LJFP menambahkan langkah sorting yang
 * terbukti meningkatkan load balance dan menekan makespan pada workload
 * sangat heterogen (highly skewed task lengths).
 */
public class MctBroker extends DatacenterBrokerSimple {

    public MctBroker(final CloudSimPlus simulation) {
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

        // ── MCT: Tidak ada sorting ─────────────────────────────────────────────
        // Task diproses dalam urutan submission delay (waktu kedatangan),
        // sama seperti dataset aslinya. Ini adalah perbedaan utama dari LJFP.
        List<Cloudlet> orderedList = new ArrayList<>(waitingList);
        orderedList.sort(Comparator.comparingDouble(Cloudlet::getSubmissionDelay));

        // Inisialisasi waktu tersedia setiap VM
        Map<Long, Double> vmReadyTime = new HashMap<>();
        for (Vm vm : vmList) {
            vmReadyTime.put(vm.getId(), 0.0);
        }

        // Greedy min-ECT (identik dengan LJFP, tapi input tidak disortir)
        for (Cloudlet cloudlet : orderedList) {
            long   taskLength  = cloudlet.getLength();
            double arrivalTime = cloudlet.getSubmissionDelay();

            Vm     bestVm      = null;
            double minEct      = Double.MAX_VALUE;
            double bestFinish  = 0.0;

            for (Vm vm : vmList) {
                double startTime = Math.max(arrivalTime, vmReadyTime.get(vm.getId()));
                double execTime  = (double) taskLength / vm.getMips();
                double ect       = startTime + execTime;

                if (ect < minEct) {
                    minEct     = ect;
                    bestFinish = ect;
                    bestVm     = vm;
                }
            }

            if (bestVm != null) {
                cloudlet.setVm(bestVm);
                vmReadyTime.put(bestVm.getId(), bestFinish);
            }
        }

        super.requestDatacentersToCreateWaitingCloudlets();
    }
}
