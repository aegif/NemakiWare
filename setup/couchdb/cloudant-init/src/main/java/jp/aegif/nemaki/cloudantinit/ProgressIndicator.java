package jp.aegif.nemaki.cloudantinit;

/**
 * Simple progress indicator for console output
 */
public class ProgressIndicator {
    private final int total;
    private int current;
    private final long startTime;
    private static final int BAR_LENGTH = 50;

    public ProgressIndicator(int total) {
        this.total = total;
        this.current = 0;
        this.startTime = System.currentTimeMillis();
        printProgress();
    }

    public void indicate() {
        indicate(1);
    }

    public void indicate(int count) {
        current += count;
        if (current > total) {
            current = total;
        }
        printProgress();
    }

    private void printProgress() {
        if (total <= 0) {
            return;
        }

        float percentage = (float) current / total;
        int progress = Math.round(percentage * BAR_LENGTH);
        
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < BAR_LENGTH; i++) {
            if (i < progress) {
                bar.append("=");
            } else if (i == progress) {
                bar.append(">");
            } else {
                bar.append(" ");
            }
        }
        bar.append("]");
        
        long elapsedTime = System.currentTimeMillis() - startTime;
        String timeStr = formatTime(elapsedTime);
        
        System.out.print("\r" + bar + " " + Math.round(percentage * 100) + "% " + 
                         current + "/" + total + " (" + timeStr + ")");
        
        if (current >= total) {
            System.out.println();
        }
    }

    private String formatTime(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        seconds %= 60;
        
        return String.format("%02d:%02d", minutes, seconds);
    }
}
