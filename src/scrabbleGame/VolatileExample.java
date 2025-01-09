package scrabbleGame;

public class VolatileExample {
    private volatile boolean running = true; // Volatile

    public void start() {
        Thread worker = new Thread(() -> {
            System.out.println("Worker thread started...");
            while (running) {
                // Simulating some work
            }
            System.out.println("Worker thread stopped.");
        });

        worker.start();
    }

    public void stop() {
        System.out.println("Requesting worker thread to stop...");
        running = false;
    }

    public static void main(String[] args) throws InterruptedException {
        VolatileExample example = new VolatileExample();
        example.start();

        // Let the worker thread run for a while
        Thread.sleep(2000);

        // Request the worker thread to stop
        example.stop();
    }
}
