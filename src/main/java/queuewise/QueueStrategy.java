package queuewise;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;

interface QueueStrategy {
    void add(Customer customer);
    Customer next();
    int size();
    List<Customer> waiting();
}

abstract class WaitingQueue implements QueueStrategy {
    private final Queue<Customer> customers;

    WaitingQueue(Queue<Customer> customers) {
        this.customers = customers;
    }

    public void add(Customer customer) {
        customers.add(customer);
    }

    public Customer next() {
        return customers.poll();
    }

    public int size() {
        return customers.size();
    }

    public List<Customer> waiting() {
        return new ArrayList<>(customers);
    }
}

class FifoQueue extends WaitingQueue {
    FifoQueue() {
        super(new LinkedList<>());
    }
}

class PriorityWaitingQueue extends WaitingQueue {
    private static final Comparator<Customer> ORDER =
            Comparator.comparing(Customer::priority).thenComparingInt(Customer::arrival)
                    .thenComparingInt(Customer::id);

    PriorityWaitingQueue() {
        super(new PriorityQueue<>(ORDER));
    }

    @Override
    public List<Customer> waiting() {
        List<Customer> ordered = super.waiting();
        ordered.sort(ORDER);
        return ordered;
    }
}
