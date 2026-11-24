package queuewise;

import java.io.Serializable;

public record Customer(int id, int arrival, int service, Priority priority) implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Priority {
        HIGH, MEDIUM, NORMAL
    }

    public Customer {
        if (id < 1 || arrival < 0 || arrival > 10080 || service < 1 || service > 1440 || priority == null) {
            throw new InvalidInputException("Use arrival minutes from 0 to 10080 and service minutes from 1 to 1440.");
        }
    }
}
