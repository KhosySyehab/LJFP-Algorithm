package com.kelompok6;

import org.cloudsimplus.allocationpolicies.VmAllocationPolicyBestFit;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.power.models.PowerModelHostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudsimplus.schedulers.vm.VmSchedulerTimeShared;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import java.util.*;

/**
 * Builder untuk arsitektur datacenter Kelompok 6 — Algoritma LJFP.
 *
 * Spesifikasi sesuai Draft Desain Project Kelompok 6:
 * ─────────────────────────────────────────────────────────────────────────────
 * 1 Datacenter (x86, Linux, Xen Hypervisor)
 *
 * Host (3 unit — sangat heterogen agar LJFP dapat bekerja maksimal):
 * ┌───────────────────┬──────┬───────────┬─────────┬──────────┬─────────┐
 * │ Kelas Host        │ Jml  │ CPU Cores │MIPS/core│  RAM     │ Storage │
 * ├───────────────────┼──────┼───────────┼─────────┼──────────┼─────────┤
 * │ Host-Performance  │  1   │    16     │  4.000  │  32 GB   │  2 TB   │
 * │ Host-Standard     │  1   │     8     │  2.000  │  16 GB   │  1 TB   │
 * │ Host-Eco          │  1   │     4     │  1.000  │   8 GB   │ 500 GB  │
 * └───────────────────┴──────┴───────────┴─────────┴──────────┴─────────┘
 *
 * VM (20 unit — heterogen ekstrem agar logika LJFP dapat diuji):
 * ┌───────────────┬──────┬───────┬───────────┬──────┬──────────────────────┐
 * │ Tipe VM       │ Jml  │ vCPU  │ MIPS/vCPU │ RAM  │ Target Alokasi LJFP  │
 * ├───────────────┼──────┼───────┼───────────┼──────┼──────────────────────┤
 * │ VM-Fastest    │  5   │   4   │   4.000   │ 8 GB │ Top 20% task terberat│
 * │ VM-Standard   │  7   │   2   │   2.000   │ 4 GB │ Beban kerja menengah │
 * │ VM-Eco        │  8   │   1   │   1.000   │ 2 GB │ Beban kerja teringan │
 * └───────────────┴──────┴───────┴───────────┴──────┴──────────────────────┘
 *
 * Kebijakan Alokasi VM : Best-Fit
 * Penjadwalan Task     : Non-preemptive (Space-Shared)
 */
public class DatacenterBuilder {

    // ── Host specifications ──────────────────────────────────────────────────
    // {MIPS_per_core, RAM_GB, num_cores, num_units}
    private static final int[][] HOST_TYPES = {
        {4000, 32, 16, 1},  // Host-Performance: 16 cores, 4000 MIPS/core, 32 GB
        {2000, 16,  8, 1},  // Host-Standard   :  8 cores, 2000 MIPS/core, 16 GB
        {1000,  8,  4, 1},  // Host-Eco        :  4 cores, 1000 MIPS/core,  8 GB
    };
    private static final String[] HOST_NAMES = {"Performance", "Standard", "Eco"};

    // Storage (MB) dan bandwidth (Mbps) per host
    private static final long HOST_STORAGE_MB_PERF    = 2_000_000L; // 2 TB
    private static final long HOST_STORAGE_MB_STANDARD = 1_000_000L; // 1 TB
    private static final long HOST_STORAGE_MB_ECO      =   500_000L; // 500 GB
    private static final long[] HOST_STORAGE = {
        HOST_STORAGE_MB_PERF, HOST_STORAGE_MB_STANDARD, HOST_STORAGE_MB_ECO
    };
    private static final long HOST_BW_MBPS = 10_000; // 10 Gbps

    // SPECpower benchmark power model: {idle_W, max_W}
    // Source: Beloglazov & Buyya (2012) — enterprise server benchmarks
    private static final double[][] POWER_SPECS = {
        {140.0, 300.0},   // Host-Performance  (High-end server, e.g. IBM System x3850)
        {105.0, 175.0},   // Host-Standard     (Mid-range, e.g. IBM System x3250 M2)
        { 93.7, 135.0},   // Host-Eco          (Entry-level, e.g. HP ProLiant ML110 G5)
    };

    // ── VM specifications ────────────────────────────────────────────────────
    // {PE_count, RAM_MB, MIPS_per_PE, num_units}
    // Kalkulasi kapasitas: 5×4GB + 7×2GB + 8×1GB = 20+14+8 = 42 GB < 56 GB host ✓
    private static final int[][] VM_TYPES = {
        {4, 4096, 4000, 5},  // VM-Fastest  : 5 units, 4 vCPU, 4000 MIPS, 4 GB
        {2, 2048, 2000, 7},  // VM-Standard : 7 units, 2 vCPU, 2000 MIPS, 2 GB
        {1, 1024, 1000, 8},  // VM-Eco      : 8 units, 1 vCPU, 1000 MIPS, 1 GB
    };
    private static final String[] VM_CLASS_NAMES = {"VM-Fastest", "VM-Standard", "VM-Eco"};
    private static final long VM_STORAGE_MB = 10_000; // 10 GB per VM
    private static final long VM_BW_MBPS = 250;       // 250 Mbps per VM

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Membangun 1 datacenter dengan 3 host heterogen dan 20 VM.
     * @return DatacenterResult berisi datacenter dan daftar VM
     */
    public static DatacenterResult build(CloudSimPlus simulation) {
        List<Host> hostList = createHosts();
        List<Vm>   vmList   = createVms();

        // Best-Fit: alokasikan VM ke host dengan resource paling mendekati kebutuhan
        Datacenter datacenter = new DatacenterSimple(simulation, hostList, new VmAllocationPolicyBestFit());

        printDatacenterSummary(hostList, vmList);

        return new DatacenterResult(datacenter, vmList);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private static List<Host> createHosts() {
        List<Host> hosts = new ArrayList<>();

        for (int t = 0; t < HOST_TYPES.length; t++) {
            int mipsPerCore = HOST_TYPES[t][0];
            int ramGB       = HOST_TYPES[t][1];
            int numCores    = HOST_TYPES[t][2];
            int numUnits    = HOST_TYPES[t][3];

            for (int u = 0; u < numUnits; u++) {
                // Create PE (Processing Element) list
                List<Pe> peList = new ArrayList<>();
                for (int pe = 0; pe < numCores; pe++) {
                    peList.add(new PeSimple(mipsPerCore));
                }

                long ramMB = (long) ramGB * 1024;
                Host host = new HostSimple(ramMB, HOST_BW_MBPS, HOST_STORAGE[t], peList)
                        .setVmScheduler(new VmSchedulerTimeShared());

                // SPECpower linear power model
                host.setPowerModel(new PowerModelHostSimple(
                        POWER_SPECS[t][1],  // max power (W)
                        POWER_SPECS[t][0]   // idle power (W)
                ));
                host.enableUtilizationStats();
                hosts.add(host);

                System.out.printf("  [Host] %-14s #%d : %d core x %d MIPS, %d GB RAM, %.0f GB Storage%n",
                        "Host-" + HOST_NAMES[t], u + 1,
                        numCores, mipsPerCore, ramGB,
                        HOST_STORAGE[t] / 1000.0);
            }
        }
        return hosts;
    }

    private static List<Vm> createVms() {
        List<Vm> vms = new ArrayList<>();

        for (int t = 0; t < VM_TYPES.length; t++) {
            int pes      = VM_TYPES[t][0];
            int ramMB    = VM_TYPES[t][1];
            int mips     = VM_TYPES[t][2];
            int count    = VM_TYPES[t][3];

            for (int i = 0; i < count; i++) {
                Vm vm = new VmSimple(mips, pes)
                        .setRam(ramMB)
                        .setBw(VM_BW_MBPS)
                        .setSize(VM_STORAGE_MB)
                        // Space-Shared = non-preemptive execution
                        .setCloudletScheduler(new CloudletSchedulerSpaceShared());
                vms.add(vm);
            }
            System.out.printf("  [VM]   %-12s : %d units, %d vCPU x %d MIPS, %d GB RAM%n",
                    VM_CLASS_NAMES[t], count, pes, mips, ramMB / 1024);
        }
        return vms;
    }

    private static void printDatacenterSummary(List<Host> hosts, List<Vm> vms) {
        long totalMips = hosts.stream()
                .mapToLong(h -> h.getPeList().stream().mapToLong(Pe::getCapacity).sum())
                .sum();
        long totalRamGB = hosts.stream().mapToLong(h -> h.getRam().getCapacity()).sum() / 1024;

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════╗");
        System.out.println("  ║           RINGKASAN DATACENTER KELOMPOK 6        ║");
        System.out.println("  ╠══════════════════════════════════════════════════╣");
        System.out.printf ("  ║  Jumlah Host    : %-30d║%n", hosts.size());
        System.out.printf ("  ║  Jumlah VM      : %-30d║%n", vms.size());
        System.out.printf ("  ║  Total MIPS     : %-30d║%n", totalMips);
        System.out.printf ("  ║  Total RAM Host : %-28d GB║%n", totalRamGB);
        System.out.println("  ╚══════════════════════════════════════════════════╝");
        System.out.println();
    }

    // ── Record ───────────────────────────────────────────────────────────────
    public record DatacenterResult(Datacenter datacenter, List<Vm> vmList) {}
}
