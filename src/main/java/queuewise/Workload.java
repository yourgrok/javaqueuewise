package queuewise;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

class Workload {
    enum Preset {
        CANTEEN("Canteen", 50, 3, 2, 5),
        CLINIC("Clinic", 30, 2, 5, 15),
        ADMINISTRATION("Administration", 40, 2, 3, 8);

        final String label;
        final int customers;
        final int counters;
        final int minimum;
        final int maximum;

        Preset(String label, int customers, int counters, int minimum, int maximum) {
            this.label = label;
            this.customers = customers;
            this.counters = counters;
            this.minimum = minimum;
            this.maximum = maximum;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    static List<Customer> generate(int count, int minimum, int maximum, boolean peak, long seed) {
        if (count < 1 || count > 200 || minimum < 1 || maximum > 1440 || minimum > maximum) {
            throw new InvalidInputException("Use 1–200 customers and an ordered service range from 1 to 1440.");
        }
        Random random = new Random(seed);
        List<Customer> customers = new ArrayList<>();
        int arrival = 0;
        for (int id = 1; id <= count; id++) {
            int gap = peak && id > count / 4 && id <= count * 3 / 4 ? 2 : 6;
            if (id > 1) {
                arrival += random.nextInt(gap);
            }
            int service = minimum + random.nextInt(maximum - minimum + 1);
            int roll = random.nextInt(100);
            Customer.Priority priority = Customer.Priority.NORMAL;
            if (roll < 15) {
                priority = Customer.Priority.HIGH;
            } else if (roll < 40) {
                priority = Customer.Priority.MEDIUM;
            }
            customers.add(new Customer(id, arrival, service, priority));
        }
        return customers;
    }

    static Simulation run(Simulation.Session workload, int counters, Simulation.Strategy strategy) {
        Simulation simulation = new Simulation(new Simulation.Session(
                workload.preset(), workload.customers(), counters, strategy, 0));
        simulation.runToEnd();
        return simulation;
    }
}
