package queuewise;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

public class Simulation {
    public enum Strategy {
        FIFO, PRIORITY
    }

    // A session needs only its original workload and its current simulated minute.
    public record Session(String preset, List<Customer> customers, int counters,
                          Strategy strategy, int minute) implements Serializable {
        private static final long serialVersionUID = 1L;

        public Session {
            customers = List.copyOf(customers);
            if (preset == null || customers.isEmpty() || customers.size() > 200 ||
                    counters < 1 || counters > 10 || strategy == null || minute < 0 || minute > 298081) {
                throw new InvalidInputException("Use 1–200 customers and 1–10 counters.");
            }
        }
    }

    public record Visit(Customer customer, int counter, int start, int end) {
        public int waitMinutes() {
            return start - customer.arrival();
        }
    }

    public record Summary(double averageWait, int maxWait, double averageQueue, int maxQueue,
                          int served, double turnaround, double utilization, int duration) {}

    private final Session settings;
    private final List<Customer> arrivals;
    private final QueueStrategy queue;
    private final Visit[] counters;
    private final List<Visit> completed = new ArrayList<>();
    private final List<Integer> queueLengths = new ArrayList<>();
    private int nextArrival;
    private int clock;
    private int occupiedMinutes;

    public Simulation(Session settings) {
        this.settings = settings;
        arrivals = new ArrayList<>(settings.customers());
        HashSet<Integer> uniqueIds = new HashSet<>();
        for (Customer customer : arrivals) {
            if (!uniqueIds.add(customer.id())) {
                throw new InvalidInputException("Customer IDs must be unique.");
            }
        }
        arrivals.sort(Comparator.comparingInt(Customer::arrival).thenComparingInt(Customer::id));
        counters = new Visit[settings.counters()];
        queue = settings.strategy() == Strategy.FIFO ? new FifoQueue() : new PriorityWaitingQueue();
        for (int minute = 0; minute < settings.minute() && !finished(); minute++) {
            step();
        }
    }

    public void step() {
        if (finished()) {
            return;
        }
        while (nextArrival < arrivals.size() && arrivals.get(nextArrival).arrival() == clock) {
            queue.add(arrivals.get(nextArrival));
            nextArrival++;
        }
        for (int index = 0; index < counters.length; index++) {
            if (counters[index] == null) {
                Customer customer = queue.next();
                if (customer != null) {
                    counters[index] = new Visit(customer, index + 1, clock, clock + customer.service());
                }
            }
        }
        queueLengths.add(queue.size());
        // Count the interval [clock, clock + 1), then release completed counters.
        clock++;
        for (int index = 0; index < counters.length; index++) {
            Visit visit = counters[index];
            if (visit != null) {
                occupiedMinutes++;
                if (visit.end() == clock) {
                    completed.add(visit);
                    counters[index] = null;
                }
            }
        }
    }

    public void runToEnd() {
        while (!finished()) {
            step();
        }
    }

    public boolean finished() {
        return completed.size() == arrivals.size();
    }

    public int clock() {
        return clock;
    }

    public Visit counter(int index) {
        return counters[index];
    }

    public List<Customer> waiting() {
        return queue.waiting();
    }

    public List<Visit> completed() {
        return List.copyOf(completed);
    }

    public List<Integer> queueLengths() {
        return List.copyOf(queueLengths);
    }

    public Session snapshot() {
        return new Session(settings.preset(), settings.customers(), settings.counters(), settings.strategy(), clock);
    }

    public Summary summary() {
        int totalWait = 0;
        int maxWait = 0;
        int turnaround = 0;
        for (Visit visit : completed) {
            totalWait += visit.waitMinutes();
            maxWait = Math.max(maxWait, visit.waitMinutes());
            turnaround += visit.end() - visit.customer().arrival();
        }
        int totalQueue = 0;
        int maxQueue = 0;
        for (int length : queueLengths) {
            totalQueue += length;
            maxQueue = Math.max(maxQueue, length);
        }
        int served = completed.size();
        return new Summary(totalWait / (double) Math.max(1, served), maxWait,
                totalQueue / (double) Math.max(1, clock), maxQueue, served,
                turnaround / (double) Math.max(1, served),
                100.0 * occupiedMinutes / (counters.length * Math.max(1, clock)), clock);
    }
}
