public class L1CacheMiss {
    private static final int RUNS = 10;
    private static final int DIMENSION_1 = 1024 * 1024;
    private static final int DIMENSION_2 = 128;

    private static long[][] longs;


    void cachehit(long[][] longs) {
        long sum = 0L;
        for (int i = 0; i < DIMENSION_1; i++) {
            for (int j = 0; j < DIMENSION_2; j++) {
                sum += longs[i][j];
            }
        }
    }

    void cachemiss(long[][] longs) {
        long sum = 0L;
        for (int j = 0; j < DIMENSION_2; j++) {
            for (int i = 0; i < DIMENSION_1; i++) {
                sum += longs[i][j];
            }
        }

    }

    public static void main(String[] args) throws Exception {
        longs = new long[DIMENSION_1][];
        for (int i = 0; i < DIMENSION_1; i++) {
            longs[i] = new long[DIMENSION_2];
            for (int j = 0; j < DIMENSION_2; j++) {
                longs[i][j] = 0L;
            }
        }
        System.out.println("starting....");

        final long start = System.nanoTime();
        L1CacheMiss lcm = new L1CacheMiss();

        for (int r = 0; r < RUNS; r++) {
            lcm.cachehit(longs);
            lcm.cachemiss(longs);

        }
        System.out.println("duration = " + (System.nanoTime() - start));
    }
}
