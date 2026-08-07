package oip;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ParallelTasksTest {

    @Test
    public void completionOrderNeverChangesResultOrder() {
        String previous = System.getProperty("oip.parallelism");
        try {
            System.setProperty("oip.parallelism", "4");
            List<Integer> result = ParallelTasks.mapOrdered(
                    8, null,
                    new ParallelTasks.Task<Integer>() {
                        @Override
                        public Integer run(int index) {
                            try {
                                Thread.sleep((8 - index) * 2L);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                            return Integer.valueOf(index);
                        }
                    }, null);
            assertEquals(Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7), result);
        } finally {
            restore("oip.parallelism", previous);
        }
    }

    private static void restore(String key, String previous) {
        if (previous == null) System.clearProperty(key);
        else System.setProperty(key, previous);
    }
}
