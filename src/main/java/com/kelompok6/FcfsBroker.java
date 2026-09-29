package com.kelompok6;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.vms.Vm;

import java.util.*;

/**
 * Baseline 2: FCFS (First Come First Served).
 *
 * FCFS mengalokasikan task dalam urutan waktu kedatangan secara siklik ke VM.
 * Ini adalah algoritma paling naif — tidak ada prediksi beban maupun sorting.
 * Diikutsertakan untuk memperlebar kontras perbandingan dan memperkuat
 * signifikansi hasil LJFP.
 */
public class FcfsBroker extends DatacenterBrokerSimple {

    public FcfsBroker(final CloudSimPlus simulation) {
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

        // Urutkan berdasarkan waktu kedatangan (FIFO)
        waitingList.sort(Comparator.comparingDouble(Cloudlet::getSubmissionDelay));

        int vmIndex = 0;
        for (Cloudlet cloudlet : waitingList) {
            // Alokasi round-robin ke VM yang tersedia
            cloudlet.setVm(vmList.get(vmIndex % vmList.size()));
            vmIndex++;
        }

        super.requestDatacentersToCreateWaitingCloudlets();
    }
}
