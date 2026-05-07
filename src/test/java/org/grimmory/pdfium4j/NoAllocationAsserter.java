package org.grimmory.pdfium4j;

import static org.junit.jupiter.api.Assertions.fail;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;

final class NoAllocationAsserter {

  private final ThreadMXBean threadMxBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();

  private Thread testThread;
  private long allocatedBytesBefore;

  void verifyAllocationTrackingAvailable() {
    if (!threadMxBean.isThreadAllocatedMemorySupported()) {
      throw new IllegalStateException("Thread allocation tracking is not supported by this JVM");
    }
    if (!threadMxBean.isThreadAllocatedMemoryEnabled()) {
      threadMxBean.setThreadAllocatedMemoryEnabled(true);
    }
  }

  long getAllocatedBytes() {
    return threadMxBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
  }

  void startRecording() {
    testThread = Thread.currentThread();
    allocatedBytesBefore = threadMxBean.getThreadAllocatedBytes(testThread.threadId());
  }

  void assertNoAllocations() {
    assertNoAllocations(0L);
  }

  void assertNoAllocations(long tolerance) {
    long allocatedBytesAfter = threadMxBean.getThreadAllocatedBytes(testThread.threadId());
    long delta = allocatedBytesAfter - allocatedBytesBefore;
    testThread = null;
    if (delta > tolerance) {
      fail(
          "Expected zero thread allocations (tolerance: "
              + tolerance
              + ") but observed "
              + delta
              + " allocated bytes");
    }
  }

  long calculateDelta() {
    if (testThread == null) return 0;
    return threadMxBean.getThreadAllocatedBytes(testThread.threadId()) - allocatedBytesBefore;
  }
}
