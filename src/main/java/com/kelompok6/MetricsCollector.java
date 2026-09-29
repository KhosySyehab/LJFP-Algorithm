package com.kelompok6;

import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.vms.Vm;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Kolektor dan reporter metrik evaluasi eksperimen — Kelompok 6 (LJFP).
 *
 * Lima metrik utama sesuai Draft Desain Project:
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ #  │ Metrik                        │ Formula                │ Target   │
 * ├────┼───────────────────────────────┼────────────────────────┼──────────┤
 * │ 1  │ Makespan                      │ max(FinishTime)        │ Min      │
 * │ 2  │ Total Energy Consumption(TEC) │ Σ(P_max·t_busy + P_idle·t_idle)│ Min │
 * │ 3  │ Degree of Imbalance (DI)      │ (T_max-T_min)/T_avg   │ Min      │
 * │ 4  │ Resource Utilization (RU)     │ Σt_busy / (makespan·N_vm) │ Max  │
 * │ 5  │ Throughput                    │ N_tasks / Makespan     │ Max      │
 * └────┴───────────────────────────────┴────────────────────────┴──────────┘
 *
 * Metrik tambahan untuk kelengkapan laporan:
 *   - Avg Turnaround Time, Avg Waiting Time, Load Balance Std Dev
 */
public class MetricsCollector {

    // ── Public API ───────────────────────────────────────────────────────────

    public static ExperimentResult collectMetrics(
            String algorithmName,
            List<Cloudlet> finishedCloudlets,
            List<Vm> vmList,
            List<Host> hostList,
            double simulationClock) {

        ExperimentResult r = new ExperimentResult();
        r.algorithm  = algorithmName;
        r.totalTasks = finishedCloudlets.size();

        if (finishedCloudlets.isEmpty()) return r;

        // 1. Makespan = waktu penyelesaian task terakhir
        r.makespan = finishedCloudlets.stream()
                .mapToDouble(Cloudlet::getFinishTime)
                .max().orElse(0.0);

        // 2. Avg Turnaround Time = rata-rata (FinishTime - SubmissionDelay)
        r.avgTurnaroundTime = finishedCloudlets.stream()
                .mapToDouble(c -> Math.max(0.0, c.getFinishTime() - c.getSubmissionDelay()))
                .average().orElse(0.0);

        // 3. Avg Waiting Time
        r.avgWaitingTime = finishedCloudlets.stream()
                .mapToDouble(Cloudlet::getWaitingTime)
                .average().orElse(0.0);

        // 4. Throughput = N_tasks / Makespan  (task/detik)
        r.throughput = r.makespan > 0 ? (double) r.totalTasks / r.makespan : 0.0;

        // 5. Resource Utilization (%)
        r.resourceUtilization = calcResourceUtilization(finishedCloudlets, vmList, r.makespan);

        // 6. Degree of Imbalance (DI)
        r.degreeOfImbalance = calcDegreeOfImbalance(finishedCloudlets, vmList, r.makespan);

        // 7. Load Balance Std Dev (distribusi task per VM)
        r.loadBalanceStdDev = calcLoadBalanceStdDev(finishedCloudlets, vmList);

        // 8. Total Energy Consumption — model SPECpower (Joule & kWh)
        r.totalEnergyJoules = calcSpecPowerEnergy(finishedCloudlets, hostList, r.makespan);
        r.totalEnergyKWh    = r.totalEnergyJoules / 3_600_000.0;

        return r;
    }

    // ── Metric Calculators ───────────────────────────────────────────────────

    /**
     * Resource Utilization (RU):
     *   RU = (Σ aktual_cpu_time per VM) / (Makespan × N_vm)
     * Nilai mendekati 1.0 (100%) berarti semua VM sibuk penuh sepanjang makespan.
     */
    private static double calcResourceUtilization(
            List<Cloudlet> cloudlets, List<Vm> vmList, double makespan) {

        if (makespan <= 0 || vmList.isEmpty()) return 0;

        Map<Vm, Double> vmBusyTime = new HashMap<>();
        for (Cloudlet cl : cloudlets) {
            Vm vm = cl.getVm();
            if (vm != null) {
                vmBusyTime.merge(vm, cl.getActualCpuTime(), Double::sum);
            }
        }

        double totalUtil = 0;
        for (Vm vm : vmList) {
            double busyTime = vmBusyTime.getOrDefault(vm, 0.0);
            totalUtil += Math.min(100.0, (busyTime / makespan) * 100.0);
        }
        return totalUtil / vmList.size();
    }

    /**
     * Degree of Imbalance (DI):
     *   DI = (T_max - T_min) / T_avg
     *
     * T_max, T_min, T_avg = waktu total eksekusi VM terlama, tercepat, dan rata-rata.
     * DI = 0 berarti beban sempurna merata; DI besar berarti ada bottleneck ekstrem.
     */
    private static double calcDegreeOfImbalance(
            List<Cloudlet> cloudlets, List<Vm> vmList, double makespan) {

        if (vmList.isEmpty()) return 0;

        // Hitung total CPU time per VM
        Map<Vm, Double> vmLoad = new HashMap<>();
        for (Vm vm : vmList) vmLoad.put(vm, 0.0);

        for (Cloudlet cl : cloudlets) {
            Vm vm = cl.getVm();
            if (vm != null) {
                vmLoad.merge(vm, cl.getActualCpuTime(), Double::sum);
            }
        }

        // Hanya hitung VM yang mendapatkan setidaknya 1 task
        List<Double> loads = new ArrayList<>(vmLoad.values());
        loads.removeIf(v -> v == 0.0);
        if (loads.isEmpty()) return 0;

        double tMax = Collections.max(loads);
        double tMin = Collections.min(loads);
        double tAvg = loads.stream().mapToDouble(Double::doubleValue).average().orElse(1.0);

        return tAvg > 0 ? (tMax - tMin) / tAvg : 0;
    }

    /**
     * Total Energy Consumption (TEC) — Model SPECpower:
     *   E_host = P_max × t_busy + P_idle × (Makespan − t_busy)
     *   TEC    = Σ E_host  (dalam Joule)
     */
    private static double calcSpecPowerEnergy(
            List<Cloudlet> cloudlets, List<Host> hostList, double makespan) {

        if (makespan <= 0) return 0;

        // Akumulasi waktu CPU aktif per host
        Map<Host, Double> hostBusyCpuTime = new HashMap<>();
        for (Cloudlet cl : cloudlets) {
            Vm vm = cl.getVm();
            if (vm != null && vm.getHost() != null) {
                Host host = vm.getHost();
                double cpuSecs = cl.getActualCpuTime() * cl.getPesNumber();
                hostBusyCpuTime.merge(host, cpuSecs, Double::sum);
            }
        }

        double totalJoules = 0.0;
        for (Host host : hostList) {
            double totalCpuSec  = hostBusyCpuTime.getOrDefault(host, 0.0);
            int    hostPes      = (int) host.getPesNumber();
            double hostBusyTime = Math.min(makespan, totalCpuSec / Math.max(1, hostPes));
            double hostIdleTime = Math.max(0.0, makespan - hostBusyTime);

            double pIdle = host.getPowerModel().getPower(0.0);
            double pMax  = host.getPowerModel().getPower(1.0);

            totalJoules += (pMax * hostBusyTime) + (pIdle * hostIdleTime);
        }
        return totalJoules;
    }

    /**
     * Standard Deviasi jumlah task yang dialokasikan per VM (Load Balance).
     * Nilai mendekati 0 = distribusi sangat merata.
     */
    private static double calcLoadBalanceStdDev(List<Cloudlet> cloudlets, List<Vm> vmList) {
        if (vmList.isEmpty()) return 0;

        Map<Vm, Long> tasksPerVm = cloudlets.stream()
                .filter(cl -> cl.getVm() != null)
                .collect(Collectors.groupingBy(Cloudlet::getVm, Collectors.counting()));

        double mean     = (double) cloudlets.size() / vmList.size();
        double variance = 0;
        for (Vm vm : vmList) {
            long count = tasksPerVm.getOrDefault(vm, 0L);
            variance  += Math.pow(count - mean, 2);
        }
        return Math.sqrt(variance / vmList.size());
    }

    // ── Reporting ────────────────────────────────────────────────────────────

    public static void printReport(ExperimentResult r) {
        System.out.println();
        System.out.println("  " + "═".repeat(68));
        System.out.printf("  ║  HASIL EKSPERIMEN — %-46s║%n", r.algorithm);
        System.out.println("  " + "═".repeat(68));
        System.out.printf("  ║  %-38s : %-25d║%n", "Total Tasks Selesai",  r.totalTasks);
        System.out.printf("  ║  %-38s : %-22.4f s║%n", "① Makespan",          r.makespan);
        System.out.printf("  ║  %-38s : %-22.2f J║%n", "② Total Energy (TEC) [Joule]", r.totalEnergyJoules);
        System.out.printf("  ║  %-38s : %-22.6f ║%n", "   Total Energy (TEC) [kWh]",  r.totalEnergyKWh);
        System.out.printf("  ║  %-38s : %-22.6f ║%n", "③ Degree of Imbalance (DI)",   r.degreeOfImbalance);
        System.out.printf("  ║  %-38s : %-21.4f %%║%n","④ Resource Utilization (RU)",  r.resourceUtilization);
        System.out.printf("  ║  %-38s : %-17.6f task/s║%n","⑤ Throughput",            r.throughput);
        System.out.println("  " + "─".repeat(68));
        System.out.printf("  ║  %-38s : %-22.4f s║%n", "   Avg Turnaround Time",  r.avgTurnaroundTime);
        System.out.printf("  ║  %-38s : %-22.4f s║%n", "   Avg Waiting Time",      r.avgWaitingTime);
        System.out.printf("  ║  %-38s : %-22.4f ║%n",  "   Load Balance (Std Dev)", r.loadBalanceStdDev);
        System.out.println("  " + "═".repeat(68));
    }

    public static void printComparisonTable(List<ExperimentResult> results) {
        System.out.println();
        System.out.println("+" + "─".repeat(100) + "+");
        System.out.println("|" + center("TABEL PERBANDINGAN PERFORMA ALGORITMA", 100) + "|");
        System.out.println("+" + "─".repeat(100) + "+");

        // Header baris
        System.out.printf("| %-26s |", "Metrik Evaluasi");
        for (ExperimentResult r : results) {
            System.out.printf(" %17s |", shortName(r.algorithm));
        }
        if (results.size() >= 2) {
            System.out.printf(" %17s |", "LJFP vs MCT");
        }
        System.out.println();
        System.out.println("+" + "─".repeat(100) + "+");

        ExperimentResult ljfp = results.stream().filter(r -> r.algorithm.contains("LJFP")).findFirst().orElse(results.get(0));
        ExperimentResult mct  = results.stream().filter(r -> r.algorithm.contains("MCT")).findFirst().orElse(null);

        printRow("① Makespan (s)",              results, r -> r.makespan,             "%.2f",   ljfp, mct, true);
        printRow("② TEC (Joule)",               results, r -> r.totalEnergyJoules,   "%.1f",   ljfp, mct, true);
        printRow("② TEC (kWh)",                 results, r -> r.totalEnergyKWh,      "%.6f",   ljfp, mct, true);
        printRow("③ Degree of Imbalance (DI)",  results, r -> r.degreeOfImbalance,   "%.6f",   ljfp, mct, true);
        printRow("④ Resource Utilization (%)",  results, r -> r.resourceUtilization, "%.2f",   ljfp, mct, false);
        printRow("⑤ Throughput (task/s)",        results, r -> r.throughput,          "%.6f",   ljfp, mct, false);
        printRow("   Turnaround Time (s)",       results, r -> r.avgTurnaroundTime,   "%.2f",   ljfp, mct, true);
        printRow("   Waiting Time (s)",          results, r -> r.avgWaitingTime,      "%.2f",   ljfp, mct, true);
        printRow("   Load Balance (Std Dev)",    results, r -> r.loadBalanceStdDev,   "%.2f",   ljfp, mct, true);

        System.out.println("+" + "─".repeat(100) + "+");
        System.out.println("  Catatan: (-) pada LJFP vs MCT = perbaikan (lebih kecil lebih baik untuk Makespan/TEC/DI)");
    }

    private static void printRow(String label, List<ExperimentResult> results,
                                 java.util.function.Function<ExperimentResult, Double> getter,
                                 String fmt,
                                 ExperimentResult ljfp, ExperimentResult mct,
                                 boolean lowerIsBetter) {

        System.out.printf("| %-26s |", label);
        for (ExperimentResult r : results) {
            System.out.printf(" %17s |", String.format(fmt, getter.apply(r)));
        }

        if (ljfp != null && mct != null) {
            double ljfpVal = getter.apply(ljfp);
            double mctVal  = getter.apply(mct);
            double pct = mctVal != 0 ? ((ljfpVal - mctVal) / mctVal) * 100.0 : 0;
            String sign = pct > 0 ? "+" : "";
            System.out.printf(" %16s%% |", sign + String.format("%.2f", pct));
        }
        System.out.println();
    }

    public static void printCloudletSample(List<Cloudlet> cloudlets, String algorithm, int maxRows) {
        System.out.printf("%n  === Sampel Eksekusi Task (%s) — %d task ===%n", algorithm, maxRows);
        System.out.printf("  %-10s │ %-8s │ %-12s │ %-12s │ %-12s │ %-12s%n",
                "CloudletID", "VM ID", "Length(MI)", "ExecTime(s)", "Start(s)", "Finish(s)");
        System.out.println("  " + "─".repeat(76));

        cloudlets.stream()
                .sorted(Comparator.comparingLong(Cloudlet::getId))
                .limit(maxRows)
                .forEach(cl -> {
                    long vmId = cl.getVm() != null ? cl.getVm().getId() : -1;
                    System.out.printf("  %-10d │ %-8d │ %-12d │ %-12.4f │ %-12.4f │ %-12.4f%n",
                            cl.getId(), vmId, cl.getLength(),
                            cl.getActualCpuTime(), cl.getExecStartTime(), cl.getFinishTime());
                });

        if (cloudlets.size() > maxRows) {
            System.out.printf("  ... dan %d task lainnya selesai.%n", cloudlets.size() - maxRows);
        }
    }

    public static void exportToCsv(List<ExperimentResult> results, String filename) throws IOException {
        java.io.File file = new java.io.File(filename);
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        try (PrintWriter w = new PrintWriter(new FileWriter(file))) {
            w.println("Algorithm,Dataset,TotalTasks,Makespan_s,TEC_Joule,TEC_kWh," +
                      "DegreeOfImbalance,ResourceUtilization_pct,Throughput_task_s," +
                      "AvgTurnaround_s,AvgWaitingTime_s,LoadBalanceStdDev");
            for (ExperimentResult r : results) {
                w.printf("%s,%s,%d,%.4f,%.2f,%.6f,%.6f,%.4f,%.6f,%.4f,%.4f,%.4f%n",
                        r.algorithm, r.dataset, r.totalTasks,
                        r.makespan, r.totalEnergyJoules, r.totalEnergyKWh,
                        r.degreeOfImbalance, r.resourceUtilization, r.throughput,
                        r.avgTurnaroundTime, r.avgWaitingTime, r.loadBalanceStdDev);
            }
        }
        System.out.printf("  [CSV] Disimpan ke: %s%n", filename);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String shortName(String algo) {
        if (algo.contains("LJFP")) return "LJFP (Diusulkan)";
        if (algo.contains("MCT"))  return "MCT (Baseline 1)";
        if (algo.contains("FCFS")) return "FCFS (Baseline 2)";
        return algo.length() > 17 ? algo.substring(0, 17) : algo;
    }

    private static String center(String text, int width) {
        int pad = (width - text.length()) / 2;
        return " ".repeat(Math.max(0, pad)) + text +
               " ".repeat(Math.max(0, width - text.length() - pad));
    }

    // ── Result record ────────────────────────────────────────────────────────

    public static class ExperimentResult {
        public String algorithm;
        public String dataset          = "";
        public int    totalTasks;
        public double makespan;
        public double avgTurnaroundTime;
        public double avgWaitingTime;
        public double throughput;
        public double resourceUtilization;
        public double degreeOfImbalance;
        public double loadBalanceStdDev;
        public double totalEnergyJoules;
        public double totalEnergyKWh;
    }
}
