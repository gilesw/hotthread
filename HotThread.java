/*
 * Main.java
 *
 * Created on 27 August 2007, 11:11
 *
 * To change this template, choose Tools | Template Manager
 * and open the template in the editor.
 */

package hotthread;


import com.sun.tools.attach.VirtualMachine;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.Collections;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

/**
 *
 * @author bchapman
 */
public class Main {

    /** Creates a new instance of Main */
    public Main() {
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String args[]) throws Exception {
        if (args.length != 1) {
            System.err.println("Please provide process id");
            System.exit(-1);
        }
//        System.out.format("java.library.path=%s%n", System.getProperty("java.library.path"));
//        System.out.format("java.home=%s%n", System.getProperty("java.home"));
//        System.out.format("attach is %s%n",System.mapLibraryName("attach"));
        VirtualMachine vm = VirtualMachine.attach(args[0]);
        String connectorAddr = vm.getAgentProperties().getProperty(
            "com.sun.management.jmxremote.localConnectorAddress");
        if (connectorAddr == null) {
            String agent = vm.getSystemProperties().getProperty(
                "java.home")+File.separator+"lib"+File.separator+
                "management-agent.jar";
            vm.loadAgent(agent);
            connectorAddr = vm.getAgentProperties().getProperty(
                "com.sun.management.jmxremote.localConnectorAddress");
        }
        JMXServiceURL serviceURL = new JMXServiceURL(connectorAddr);
        JMXConnector connector = JMXConnectorFactory.connect(serviceURL);
        MBeanServerConnection mbsc = connector.getMBeanServerConnection();
        ObjectName objName = new ObjectName(
            ManagementFactory.THREAD_MXBEAN_NAME);
        Set<ObjectName> mbeans = mbsc.queryNames(objName, null);
        for (ObjectName name: mbeans) {
            ThreadMXBean threadBean;
            threadBean = ManagementFactory.newPlatformMXBeanProxy(
                mbsc, name.toString(), ThreadMXBean.class);
//            long threadIds[] = threadBean.getAllThreadIds();
//            for (long threadId: threadIds) {
//                ThreadInfo threadInfo = threadBean.getThreadInfo(threadId);
//                System.out.println(threadInfo.getThreadName() + " / " +
//                    threadInfo.getThreadState());
//            }
            new Main().doHotDetection(threadBean);
        }
    }

    private void doHotDetection(ThreadMXBean threadBean) throws InterruptedException {
        if(threadBean.isThreadCpuTimeSupported()) {
            if(! threadBean.isThreadCpuTimeEnabled()) threadBean.setThreadCpuTimeEnabled(true);
        } else {
            throw new IllegalStateException("MBean doesn't support thread CPU Time");
        }
        Map<Long,MyThreadInfo> threadInfos = new HashMap<Long,MyThreadInfo>();
        for(long threadId: threadBean.getAllThreadIds()) {
            long cpu = threadBean.getThreadCpuTime(threadId);
            ThreadInfo info = threadBean.getThreadInfo(threadId,0);
            threadInfos.put(threadId,new MyThreadInfo(cpu,info));
        }
        try {
            Thread.sleep(500);
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
        for(long threadId: threadBean.getAllThreadIds()) {
            long cpu = threadBean.getThreadCpuTime(threadId);
            ThreadInfo info = threadBean.getThreadInfo(threadId,0);
            MyThreadInfo data = threadInfos.get(threadId);
            if(data != null) {
                data.setDelta(cpu,info);
            }
        }
        // sort by delta CPU time on thread.
        List<MyThreadInfo> hotties = new ArrayList<MyThreadInfo>(threadInfos.values());
        // skip that for now
        Collections.sort(hotties, new Comparator<MyThreadInfo>() {
            public int compare(MyThreadInfo o1, MyThreadInfo o2) {
                return (int)(o2.cpuTime - o1.cpuTime);
            }
        });
//        for(MyThreadInfo inf : hotties) {
//            if(inf.deltaDone) {
//                System.out.format("%5.2f %d/%d %d/%d %s%n",
//                    inf.cpuTime/1E7,
//                    inf.blockedCount,
//                    inf.blockedTime,
//                    inf.waitedCount,
//                    inf.waitedtime,
//                    inf.info.getThreadName()
//                    );
//            }
//        }
        // analyse N stack traces for M busiest threads
        int M = 3;
        int N = 10;
        int DELAY = 10;
        StackTraceElement[][] stacks = new StackTraceElement[N][];
        long[] ids = new long[M];
        for(int i=0; i < M; i++) {
            MyThreadInfo info = hotties.get(i);
            ids[i] = info.info.getThreadId();
        }
        ThreadInfo[][] allInfos = new ThreadInfo[N][];
        for(int j=0; j < N; j++) {
            allInfos[j] = threadBean.getThreadInfo(ids,Integer.MAX_VALUE);
            Thread.sleep(DELAY);
        }
        for(int t=0; t < M; t++) {
            System.out.format("%n%4.1f%% CPU Usage by Thread '%s'%n",hotties.get(t).cpuTime/1E7, allInfos[0][t].getThreadName());
            // for each snapshot (2nd array index) find later snapshot for same thread with max number of
            // identical StackTraceElements (starting from end of each)
            int[] maxSims = new int[N];
            boolean[] done = new boolean[N];
            for(int i=0; i < N; i++) {
                if(done[i])continue;
                int maxSim = 1;
                boolean[] similars = new boolean[N];
                for(int j=i+1; j < N; j++) {
                    if(done[j])continue;
                    int similarity = similarity(allInfos[i][t],allInfos[j][t]);
                    if(similarity > maxSim) {
                        maxSim = similarity;
                        similars = new boolean[N];
                    }
                    if(similarity == maxSim) similars[j] = true;
                }
                // print out trace maxSim levels of i, and mark similar ones as done
                int count=1;
                for(int j=i+1; j < N; j++) {
                    if(similars[j]) {
                        done[j] = true;
                        count++;
                    }
                }
                StackTraceElement[] show = allInfos[i][t].getStackTrace();
                if(count == 1) {
                    System.out.format("  Unique snapshot%n");
                    for(int l = 0; l < show.length; l++) {
                        System.out.format("    %s%n",show[l]);
                    }
                } else {
                    System.out.format("  %d/%d snapshots sharing following %d elements%n",count,N,maxSim);
                    for(int l = show.length - maxSim; l < show.length; l++) {
                        System.out.format("    %s%n",show[l]);
                    }
                }
            }
        }
    }

    private int similarity(ThreadInfo threadInfo, ThreadInfo threadInfo0) {
        StackTraceElement[] s1 = threadInfo.getStackTrace();
        StackTraceElement[] s2 = threadInfo0.getStackTrace();
        int i=s1.length-1;
        int j=s2.length-1;
        int rslt=0;
        while(i >= 0 && j >=0 && s1[i].equals(s2[j])) {
            rslt++;
            i--;
            j--;
        }
        return rslt;
    }


    class MyThreadInfo {
        long cpuTime;
        long blockedCount;
        long blockedTime;
        long waitedCount;
        long waitedtime;
        boolean deltaDone;
        ThreadInfo info;
        MyThreadInfo(long cpuTime, ThreadInfo info) {
            blockedCount =info.getBlockedCount();
            blockedTime = info.getBlockedTime();
            waitedCount = info.getWaitedCount();
            waitedtime = info.getWaitedTime();
            this.cpuTime = cpuTime;
        }

        void setDelta(long cpuTime, ThreadInfo info) {
            if(deltaDone) throw new IllegalStateException("setDelta already called once");
            blockedCount = info.getBlockedCount() - blockedCount;
            blockedTime = info.getBlockedTime() - blockedTime;
            waitedCount = info.getWaitedCount() - waitedCount;
            waitedtime = info.getWaitedTime() - waitedtime;
            this.cpuTime = cpuTime - this.cpuTime;
            deltaDone = true;
            this.info = info;
        }
    }
}

