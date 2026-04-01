package it.denzosoft.jvmmonitor.storage;

import it.denzosoft.jvmmonitor.model.*;

import java.io.*;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Saves and loads monitoring sessions to/from compressed binary files (.jvmsession.gz).
 * Format: GZip-compressed DataOutputStream with type markers and event counts.
 * Compatible with Java 1.6.
 */
public final class SessionSerializer {

    private static final int MAGIC = 0x4A564D53;  /* "JVMS" */
    private static final int VERSION = 1;

    /* Section markers */
    private static final byte SEC_MEMORY      = 1;
    private static final byte SEC_GC          = 2;
    private static final byte SEC_THREAD      = 3;
    private static final byte SEC_EXCEPTION   = 4;
    private static final byte SEC_CPU_SAMPLE  = 5;
    private static final byte SEC_CPU_USAGE   = 6;
    private static final byte SEC_OS          = 7;
    private static final byte SEC_JIT         = 8;
    private static final byte SEC_LOCK        = 9;
    private static final byte SEC_NETWORK     = 10;
    private static final byte SEC_ALARM       = 11;
    private static final byte SEC_SAFEPOINT   = 12;
    private static final byte SEC_ALLOC       = 13;
    private static final byte SEC_INSTR       = 14;
    private static final byte SEC_QUEUE       = 15;
    private static final byte SEC_HISTOGRAM   = 16;
    private static final byte SEC_END         = (byte) 0xFF;

    private SessionSerializer() {}

    /**
     * Save all data from an EventStore to a compressed file.
     * @param store the event store to serialize
     * @param file  the target file (.jvmsession.gz)
     */
    public static void save(EventStore store, File file) throws IOException {
        long now = System.currentTimeMillis();
        long from = 0;  /* save everything */

        FileOutputStream fos = new FileOutputStream(file);
        GZIPOutputStream gzos = new GZIPOutputStream(fos, 8192);
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(gzos, 8192));

        try {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeLong(now);  /* session save timestamp */

            /* ── Memory Snapshots ──────────── */
            List list = store.getMemorySnapshots(from, now);
            out.writeByte(SEC_MEMORY);
            out.writeInt(list.size());
            for (int i = 0; i < list.size(); i++) {
                MemorySnapshot m = (MemorySnapshot) list.get(i);
                out.writeLong(m.getTimestamp());
                out.writeLong(m.getHeapUsed());
                out.writeLong(m.getHeapMax());
                out.writeLong(m.getNonHeapUsed());
                out.writeLong(m.getNonHeapMax());
            }

            /* ── GC Events ─────────────────── */
            List gcList = store.getGcEvents(from, now);
            out.writeByte(SEC_GC);
            out.writeInt(gcList.size());
            for (int i = 0; i < gcList.size(); i++) {
                GcEvent e = (GcEvent) gcList.get(i);
                out.writeLong(e.getTimestamp());
                out.writeInt(e.getGcType());
                out.writeLong(e.getDurationNanos());
                out.writeInt(e.getGcCount());
                out.writeInt(e.getFullGcCount());
                out.writeLong(e.getHeapBefore());
                out.writeLong(e.getHeapAfter());
                out.writeLong(e.getEdenBefore());
                out.writeLong(e.getEdenAfter());
                out.writeLong(e.getOldGenBefore());
                out.writeLong(e.getOldGenAfter());
                writeString(out, e.getCause());
                out.writeDouble(e.getProcessCpuAtGc());
            }

            /* ── Threads ───────────────────── */
            List threads = store.getLatestThreadInfo();
            out.writeByte(SEC_THREAD);
            out.writeInt(threads.size());
            for (int i = 0; i < threads.size(); i++) {
                ThreadInfo t = (ThreadInfo) threads.get(i);
                out.writeLong(t.getTimestamp());
                out.writeLong(t.getThreadId());
                writeString(out, t.getName());
                out.writeInt(t.getState());
                out.writeBoolean(t.isDaemon());
            }

            /* ── Exceptions ────────────────── */
            List excList = store.getExceptions(from, now);
            out.writeByte(SEC_EXCEPTION);
            out.writeInt(excList.size());
            for (int i = 0; i < excList.size(); i++) {
                ExceptionEvent e = (ExceptionEvent) excList.get(i);
                out.writeLong(e.getTimestamp());
                out.writeInt(e.getTotalThrown());
                out.writeInt(e.getTotalCaught());
                out.writeInt(e.getTotalDropped());
                writeString(out, e.getExceptionClass());
                writeString(out, e.getThrowClass());
                writeString(out, e.getThrowMethod());
                out.writeLong(e.getThrowLocation());
                out.writeBoolean(e.isCaught());
                writeString(out, e.getCatchClass());
                writeString(out, e.getCatchMethod());
                ExceptionEvent.StackFrame[] frames = e.getStackFrames();
                int frameCount = frames != null ? frames.length : 0;
                out.writeInt(frameCount);
                for (int f = 0; f < frameCount; f++) {
                    writeString(out, frames[f].getClassName());
                    writeString(out, frames[f].getMethodName());
                    out.writeInt(frames[f].getLineNumber());
                }
            }

            /* ── CPU Samples ───────────────── */
            List cpuList = store.getCpuSamples(from, now);
            out.writeByte(SEC_CPU_SAMPLE);
            out.writeInt(cpuList.size());
            for (int i = 0; i < cpuList.size(); i++) {
                CpuSample s = (CpuSample) cpuList.get(i);
                out.writeLong(s.getTimestamp());
                out.writeLong(s.getThreadId());
                CpuSample.StackFrame[] frames = s.getFrames();
                int frameCount = frames != null ? frames.length : 0;
                out.writeInt(frameCount);
                for (int f = 0; f < frameCount; f++) {
                    out.writeLong(frames[f].getMethodId());
                    out.writeInt(frames[f].getLineNumber());
                    writeString(out, frames[f].getClassName());
                    writeString(out, frames[f].getMethodName());
                }
            }

            /* ── CPU Usage ─────────────────── */
            List cpuUList = store.getCpuUsageHistory(from, now);
            out.writeByte(SEC_CPU_USAGE);
            out.writeInt(cpuUList.size());
            for (int i = 0; i < cpuUList.size(); i++) {
                CpuUsageSnapshot c = (CpuUsageSnapshot) cpuUList.get(i);
                out.writeLong(c.getTimestamp());
                out.writeDouble(c.getSystemCpuPercent());
                out.writeInt(c.getAvailableProcessors());
                out.writeDouble(c.getProcessCpuPercent());
                out.writeLong(c.getProcessUserTimeMs());
                out.writeLong(c.getProcessSystemTimeMs());
                CpuUsageSnapshot.ThreadCpuInfo[] ti = c.getTopThreads();
                int tiCount = ti != null ? ti.length : 0;
                out.writeInt(tiCount);
                for (int t = 0; t < tiCount; t++) {
                    out.writeLong(ti[t].threadId);
                    writeString(out, ti[t].threadName);
                    out.writeLong(ti[t].cpuTimeMs);
                    out.writeDouble(ti[t].cpuPercent);
                    writeString(out, ti[t].state);
                }
            }

            /* ── OS Metrics ────────────────── */
            List osList = store.getOsMetricsHistory(from, now);
            out.writeByte(SEC_OS);
            out.writeInt(osList.size());
            for (int i = 0; i < osList.size(); i++) {
                OsMetrics o = (OsMetrics) osList.get(i);
                out.writeLong(o.getTimestamp());
                out.writeInt(o.getOpenFileDescriptors());
                out.writeLong(o.getRssBytes());
                out.writeLong(o.getVmSizeBytes());
                out.writeLong(o.getVoluntaryContextSwitches());
                out.writeLong(o.getInvoluntaryContextSwitches());
                out.writeInt(o.getTcpEstablished());
                out.writeInt(o.getTcpCloseWait());
                out.writeInt(o.getTcpTimeWait());
                out.writeInt(o.getOsThreadCount());
            }

            /* ── JIT Events ────────────────── */
            List jitList = store.getJitEvents(from, now);
            out.writeByte(SEC_JIT);
            out.writeInt(jitList.size());
            for (int i = 0; i < jitList.size(); i++) {
                JitEvent j = (JitEvent) jitList.get(i);
                out.writeLong(j.getTimestamp());
                out.writeInt(j.getEventType());
                writeString(out, j.getClassName());
                writeString(out, j.getMethodName());
                out.writeInt(j.getCodeSize());
                out.writeLong(j.getCodeAddr());
                out.writeInt(j.getTotalCompiled());
            }

            /* ── Lock Events ───────────────── */
            List lockList = store.getLockEvents(from, now);
            out.writeByte(SEC_LOCK);
            out.writeInt(lockList.size());
            for (int i = 0; i < lockList.size(); i++) {
                LockEvent l = (LockEvent) lockList.get(i);
                out.writeLong(l.getTimestamp());
                out.writeInt(l.getEventType());
                writeString(out, l.getThreadName());
                writeString(out, l.getLockClassName());
                out.writeInt(l.getLockHash());
                out.writeInt(l.getTotalContentions());
                writeString(out, l.getOwnerThreadName());
                out.writeInt(l.getOwnerEntryCount());
                out.writeInt(l.getWaiterCount());
                LockEvent.StackFrame[] frames = l.getStackFrames();
                int frameCount = frames != null ? frames.length : 0;
                out.writeInt(frameCount);
                for (int f = 0; f < frameCount; f++) {
                    writeString(out, frames[f].className);
                    writeString(out, frames[f].methodName);
                }
            }

            /* ── Network Snapshots ─────────── */
            List netList = store.getNetworkHistory(from, now);
            out.writeByte(SEC_NETWORK);
            out.writeInt(netList.size());
            for (int i = 0; i < netList.size(); i++) {
                NetworkSnapshot n = (NetworkSnapshot) netList.get(i);
                out.writeLong(n.getTimestamp());
                out.writeLong(n.getActiveOpens());
                out.writeLong(n.getPassiveOpens());
                out.writeLong(n.getInSegments());
                out.writeLong(n.getOutSegments());
                out.writeLong(n.getRetransSegments());
                out.writeLong(n.getInErrors());
                out.writeLong(n.getOutResets());
                out.writeLong(n.getCurrentEstablished());
                NetworkSnapshot.SocketInfo[] socks = n.getSockets();
                int sockCount = socks != null ? socks.length : 0;
                out.writeInt(sockCount);
                for (int s = 0; s < sockCount; s++) {
                    if (socks[s] == null) {
                        out.writeBoolean(false);
                        continue;
                    }
                    out.writeBoolean(true);
                    out.writeLong(socks[s].localAddr);
                    out.writeInt(socks[s].localPort);
                    out.writeLong(socks[s].remoteAddr);
                    out.writeInt(socks[s].remotePort);
                    out.writeInt(socks[s].state);
                    out.writeLong(socks[s].txQueue);
                    out.writeLong(socks[s].rxQueue);
                    out.writeLong(socks[s].bytesIn);
                    out.writeLong(socks[s].bytesOut);
                    out.writeInt(socks[s].requestCount);
                    writeString(out, socks[s].serviceName);
                }
            }

            /* ── Alarms ────────────────────── */
            List alarmList = store.getAlarms(from, now);
            out.writeByte(SEC_ALARM);
            out.writeInt(alarmList.size());
            for (int i = 0; i < alarmList.size(); i++) {
                AlarmEvent a = (AlarmEvent) alarmList.get(i);
                out.writeLong(a.getTimestamp());
                out.writeInt(a.getAlarmType());
                out.writeInt(a.getSeverity());
                out.writeDouble(a.getValue());
                out.writeDouble(a.getThreshold());
                writeString(out, a.getMessage());
            }

            /* ── Safepoints ────────────────── */
            List spList = store.getSafepointHistory(from, now);
            out.writeByte(SEC_SAFEPOINT);
            out.writeInt(spList.size());
            for (int i = 0; i < spList.size(); i++) {
                SafepointEvent sp = (SafepointEvent) spList.get(i);
                out.writeLong(sp.getTimestamp());
                out.writeLong(sp.getSafepointCount());
                out.writeLong(sp.getTotalTimeMs());
                out.writeLong(sp.getSyncTimeMs());
            }

            /* ── Allocation Events ─────────── */
            List allocList = store.getAllocationEvents(from, now);
            out.writeByte(SEC_ALLOC);
            out.writeInt(allocList.size());
            for (int i = 0; i < allocList.size(); i++) {
                AllocationEvent ae = (AllocationEvent) allocList.get(i);
                out.writeLong(ae.getTimestamp());
                writeString(out, ae.getClassName());
                out.writeLong(ae.getSize());
                out.writeLong(ae.getThreadId());
                writeString(out, ae.getThreadName());
                writeString(out, ae.getAllocSite());
            }

            /* ── Instrumentation Events ────── */
            List instrList = store.getInstrumentationEvents(from, now);
            out.writeByte(SEC_INSTR);
            out.writeInt(instrList.size());
            for (int i = 0; i < instrList.size(); i++) {
                InstrumentationEvent ie = (InstrumentationEvent) instrList.get(i);
                out.writeLong(ie.getTimestamp());
                out.writeInt(ie.getEventType());
                out.writeLong(ie.getThreadId());
                writeString(out, ie.getThreadName());
                writeString(out, ie.getClassName());
                writeString(out, ie.getMethodName());
                out.writeLong(ie.getDurationNanos());
                writeString(out, ie.getContext());
                out.writeLong(ie.getTraceId());
                out.writeLong(ie.getParentTraceId());
                out.writeInt(ie.getDepth());
                out.writeBoolean(ie.isException());
            }

            /* ── Queue Stats ───────────────── */
            List qsList = store.getQueueStatsHistory(from, now);
            out.writeByte(SEC_QUEUE);
            out.writeInt(qsList.size());
            for (int i = 0; i < qsList.size(); i++) {
                QueueStats qs = (QueueStats) qsList.get(i);
                out.writeLong(qs.getTimestamp());
                QueueStats.QueueInfo[] qi = qs.getQueues();
                int qCount = qi != null ? qi.length : 0;
                out.writeInt(qCount);
                for (int q = 0; q < qCount; q++) {
                    writeString(out, qi[q].name);
                    writeString(out, qi[q].type);
                    out.writeLong(qi[q].depth);
                    out.writeLong(qi[q].enqueueRate);
                    out.writeLong(qi[q].dequeueRate);
                    out.writeInt(qi[q].consumerCount);
                    out.writeInt(qi[q].producerCount);
                    out.writeLong(qi[q].totalEnqueued);
                    out.writeLong(qi[q].totalDequeued);
                    out.writeLong(qi[q].consumerLag);
                    out.writeLong(qi[q].oldestMessageAge);
                }
            }

            /* ── Class Histograms ──────────── */
            List histList = store.getClassHistogramHistory();
            out.writeByte(SEC_HISTOGRAM);
            out.writeInt(histList.size());
            for (int i = 0; i < histList.size(); i++) {
                ClassHistogram h = (ClassHistogram) histList.get(i);
                out.writeLong(h.getTimestamp());
                out.writeLong(h.getElapsedNanos());
                ClassHistogram.Entry[] entries = h.getEntries();
                int eCount = entries != null ? entries.length : 0;
                out.writeInt(eCount);
                for (int e = 0; e < eCount; e++) {
                    if (entries[e] == null) {
                        out.writeBoolean(false);
                        continue;
                    }
                    out.writeBoolean(true);
                    writeString(out, entries[e].getClassName());
                    out.writeInt(entries[e].getInstanceCount());
                    out.writeLong(entries[e].getTotalSize());
                }
            }

            out.writeByte(SEC_END);
            out.flush();
        } finally {
            out.close();
        }
    }

    /**
     * Load a session from a compressed file into an EventStore.
     * @param store the target event store (typically a fresh InMemoryEventStore)
     * @param file  the source file
     * @return the session save timestamp
     */
    public static long load(EventStore store, File file) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        GZIPInputStream gzis = new GZIPInputStream(fis, 8192);
        DataInputStream in = new DataInputStream(new BufferedInputStream(gzis, 8192));

        try {
            int magic = in.readInt();
            if (magic != MAGIC) {
                throw new IOException("Not a JVMMonitor session file (bad magic: 0x" +
                        Integer.toHexString(magic) + ")");
            }
            int version = in.readInt();
            if (version > VERSION) {
                throw new IOException("Unsupported session version: " + version);
            }
            long saveTimestamp = in.readLong();

            while (true) {
                byte section = in.readByte();
                if (section == SEC_END) break;

                int count = in.readInt();

                switch (section) {
                    case SEC_MEMORY:
                        for (int i = 0; i < count; i++) {
                            store.storeMemorySnapshot(new MemorySnapshot(
                                    in.readLong(), in.readLong(), in.readLong(),
                                    in.readLong(), in.readLong()));
                        }
                        break;

                    case SEC_GC:
                        for (int i = 0; i < count; i++) {
                            store.storeGcEvent(new GcEvent(
                                    in.readLong(), in.readInt(), in.readLong(),
                                    in.readInt(), in.readInt(),
                                    in.readLong(), in.readLong(),
                                    in.readLong(), in.readLong(),
                                    in.readLong(), in.readLong(),
                                    readString(in), in.readDouble()));
                        }
                        break;

                    case SEC_THREAD:
                        for (int i = 0; i < count; i++) {
                            store.storeThreadInfo(new ThreadInfo(
                                    in.readLong(), in.readLong(), readString(in),
                                    in.readInt(), in.readBoolean()));
                        }
                        break;

                    case SEC_EXCEPTION:
                        for (int i = 0; i < count; i++) {
                            long ts = in.readLong();
                            int thrown = in.readInt();
                            int caught = in.readInt();
                            int dropped = in.readInt();
                            String excClass = readString(in);
                            String throwClass = readString(in);
                            String throwMethod = readString(in);
                            long throwLoc = in.readLong();
                            boolean isCaught = in.readBoolean();
                            String catchClass = readString(in);
                            String catchMethod = readString(in);
                            int frameCount = in.readInt();
                            ExceptionEvent.StackFrame[] frames =
                                    new ExceptionEvent.StackFrame[frameCount];
                            for (int f = 0; f < frameCount; f++) {
                                frames[f] = new ExceptionEvent.StackFrame(
                                        readString(in), readString(in), in.readInt());
                            }
                            store.storeException(new ExceptionEvent(ts, thrown, caught, dropped,
                                    excClass, throwClass, throwMethod, throwLoc,
                                    isCaught, catchClass, catchMethod, frames));
                        }
                        break;

                    case SEC_CPU_SAMPLE:
                        for (int i = 0; i < count; i++) {
                            long ts = in.readLong();
                            long tid = in.readLong();
                            int frameCount = in.readInt();
                            CpuSample.StackFrame[] frames = new CpuSample.StackFrame[frameCount];
                            for (int f = 0; f < frameCount; f++) {
                                frames[f] = readStackFrame(in);
                            }
                            store.storeCpuSample(new CpuSample(ts, tid, frames));
                        }
                        break;

                    case SEC_CPU_USAGE:
                        for (int i = 0; i < count; i++) {
                            long ts = in.readLong();
                            double sysCpu = in.readDouble();
                            int procs = in.readInt();
                            double procCpu = in.readDouble();
                            long userMs = in.readLong();
                            long sysMs = in.readLong();
                            int tiCount = in.readInt();
                            CpuUsageSnapshot.ThreadCpuInfo[] ti =
                                    new CpuUsageSnapshot.ThreadCpuInfo[tiCount];
                            for (int t = 0; t < tiCount; t++) {
                                ti[t] = new CpuUsageSnapshot.ThreadCpuInfo(
                                        in.readLong(), readString(in), in.readLong(),
                                        in.readDouble(), readString(in));
                            }
                            store.storeCpuUsage(new CpuUsageSnapshot(ts, sysCpu, procs,
                                    procCpu, userMs, sysMs, ti));
                        }
                        break;

                    case SEC_OS:
                        for (int i = 0; i < count; i++) {
                            store.storeOsMetrics(new OsMetrics(
                                    in.readLong(), in.readInt(), in.readLong(), in.readLong(),
                                    in.readLong(), in.readLong(),
                                    in.readInt(), in.readInt(), in.readInt(), in.readInt()));
                        }
                        break;

                    case SEC_JIT:
                        for (int i = 0; i < count; i++) {
                            store.storeJitEvent(new JitEvent(
                                    in.readLong(), in.readInt(), readString(in), readString(in),
                                    in.readInt(), in.readLong(), in.readInt()));
                        }
                        break;

                    case SEC_LOCK:
                        for (int i = 0; i < count; i++) {
                            long ts = in.readLong();
                            int evType = in.readInt();
                            String tName = readString(in);
                            String lockClass = readString(in);
                            int lockHash = in.readInt();
                            int totalCont = in.readInt();
                            String ownerName = readString(in);
                            int ownerEntry = in.readInt();
                            int waiterCnt = in.readInt();
                            int frameCount = in.readInt();
                            LockEvent.StackFrame[] frames =
                                    new LockEvent.StackFrame[frameCount];
                            for (int f = 0; f < frameCount; f++) {
                                frames[f] = new LockEvent.StackFrame(
                                        readString(in), readString(in));
                            }
                            store.storeLockEvent(new LockEvent(ts, evType, tName,
                                    lockClass, lockHash, totalCont,
                                    ownerName, ownerEntry, waiterCnt, frames));
                        }
                        break;

                    case SEC_NETWORK:
                        for (int i = 0; i < count; i++) {
                            long ts = in.readLong();
                            long ao = in.readLong();
                            long po = in.readLong();
                            long is = in.readLong();
                            long os = in.readLong();
                            long rs = in.readLong();
                            long ie = in.readLong();
                            long or2 = in.readLong();
                            long ce = in.readLong();
                            int sockCount = in.readInt();
                            NetworkSnapshot.SocketInfo[] socks =
                                    new NetworkSnapshot.SocketInfo[sockCount];
                            for (int s = 0; s < sockCount; s++) {
                                boolean present = in.readBoolean();
                                if (!present) continue;
                                socks[s] = new NetworkSnapshot.SocketInfo(
                                        in.readLong(), in.readInt(), in.readLong(), in.readInt(),
                                        in.readInt(), in.readLong(), in.readLong(),
                                        in.readLong(), in.readLong(), in.readInt(), readString(in));
                            }
                            store.storeNetworkSnapshot(new NetworkSnapshot(ts,
                                    ao, po, is, os, rs, ie, or2, ce, socks));
                        }
                        break;

                    case SEC_ALARM:
                        for (int i = 0; i < count; i++) {
                            store.storeAlarm(new AlarmEvent(
                                    in.readLong(), in.readInt(), in.readInt(),
                                    in.readDouble(), in.readDouble(), readString(in)));
                        }
                        break;

                    case SEC_SAFEPOINT:
                        for (int i = 0; i < count; i++) {
                            store.storeSafepoint(new SafepointEvent(
                                    in.readLong(), in.readLong(), in.readLong(), in.readLong()));
                        }
                        break;

                    case SEC_ALLOC:
                        for (int i = 0; i < count; i++) {
                            store.storeAllocationEvent(new AllocationEvent(
                                    in.readLong(), readString(in), in.readLong(),
                                    in.readLong(), readString(in), readString(in)));
                        }
                        break;

                    case SEC_INSTR:
                        for (int i = 0; i < count; i++) {
                            store.storeInstrumentationEvent(new InstrumentationEvent(
                                    in.readLong(), in.readInt(), in.readLong(), readString(in),
                                    readString(in), readString(in), in.readLong(),
                                    readString(in), in.readLong(), in.readLong(),
                                    in.readInt(), in.readBoolean()));
                        }
                        break;

                    case SEC_QUEUE:
                        for (int i = 0; i < count; i++) {
                            long ts = in.readLong();
                            int qCount = in.readInt();
                            QueueStats.QueueInfo[] qi = new QueueStats.QueueInfo[qCount];
                            for (int q = 0; q < qCount; q++) {
                                qi[q] = new QueueStats.QueueInfo(
                                        readString(in), readString(in), in.readLong(),
                                        in.readLong(), in.readLong(),
                                        in.readInt(), in.readInt(),
                                        in.readLong(), in.readLong(),
                                        in.readLong(), in.readLong());
                            }
                            store.storeQueueStats(new QueueStats(ts, qi));
                        }
                        break;

                    case SEC_HISTOGRAM:
                        for (int i = 0; i < count; i++) {
                            long ts = in.readLong();
                            long elapsed = in.readLong();
                            int eCount = in.readInt();
                            ClassHistogram.Entry[] entries = new ClassHistogram.Entry[eCount];
                            for (int e = 0; e < eCount; e++) {
                                boolean present = in.readBoolean();
                                if (!present) continue;
                                entries[e] = new ClassHistogram.Entry(
                                        readString(in), in.readInt(), in.readLong());
                            }
                            store.storeClassHistogram(new ClassHistogram(ts, elapsed, entries));
                        }
                        break;

                    default:
                        /* Unknown section — skip by reading count*0 or just break */
                        throw new IOException("Unknown section type: " + section);
                }
            }

            return saveTimestamp;
        } finally {
            in.close();
        }
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        if (s == null) {
            out.writeInt(-1);
        } else {
            byte[] bytes = s.getBytes("UTF-8");
            out.writeInt(bytes.length);
            out.write(bytes);
        }
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0) return null;
        if (len == 0) return "";
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, "UTF-8");
    }

    private static CpuSample.StackFrame readStackFrame(DataInputStream in) throws IOException {
        long methodId = in.readLong();
        int lineNumber = in.readInt();
        CpuSample.StackFrame frame = new CpuSample.StackFrame(methodId, lineNumber);
        frame.setClassName(readString(in));
        frame.setMethodName(readString(in));
        return frame;
    }
}
