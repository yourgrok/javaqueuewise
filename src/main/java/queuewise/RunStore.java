package queuewise;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

class RunStore {
    private final Path directory;

    record SavedRun(int id, String name, String preset, String strategy, int counters,
                    double averageWait, String savedAt) {
        @Override
        public String toString() {
            return name + "   •   " + preset + "   •   " + strategy + "   •   "
                    + counters + " counters   •   " + String.format("%.1f min", averageWait);
        }
    }

    RunStore(Path directory) {
        this.directory = directory;
    }

    private Connection connect() throws IOException, SQLException {
        Files.createDirectories(directory);
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("queuewise.db"));
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS runs (
                        id INTEGER PRIMARY KEY, name TEXT NOT NULL, preset TEXT NOT NULL,
                        strategy TEXT NOT NULL, counters INTEGER NOT NULL, average_wait REAL NOT NULL,
                        saved_at TEXT NOT NULL, workload BLOB NOT NULL
                    )
                    """);
            return connection;
        } catch (SQLException error) {
            connection.close();
            throw error;
        }
    }

    void save(String name, Simulation simulation) throws IOException, SQLException {
        Simulation.Session session = simulation.snapshot();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO runs(name, preset, strategy, counters, average_wait, saved_at, workload)
                     VALUES (?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, name);
            statement.setString(2, session.preset());
            statement.setString(3, session.strategy().name());
            statement.setInt(4, session.counters());
            statement.setDouble(5, simulation.summary().averageWait());
            statement.setString(6, LocalDateTime.now().withNano(0).toString());
            statement.setBytes(7, encode(session));
            statement.executeUpdate();
        }
    }

    List<SavedRun> list() throws IOException, SQLException {
        List<SavedRun> runs = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM runs ORDER BY id DESC");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                runs.add(new SavedRun(rows.getInt("id"), rows.getString("name"), rows.getString("preset"),
                        rows.getString("strategy"), rows.getInt("counters"), rows.getDouble("average_wait"),
                        rows.getString("saved_at")));
            }
        }
        return runs;
    }

    Simulation open(int id) throws IOException, SQLException, ClassNotFoundException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("SELECT workload FROM runs WHERE id = ?")) {
            statement.setInt(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("This run is no longer in history.");
                }
                return new Simulation(decode(rows.getBytes(1)));
            }
        }
    }

    void rename(int id, String name) throws IOException, SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("UPDATE runs SET name = ? WHERE id = ?")) {
            statement.setString(1, name);
            statement.setInt(2, id);
            statement.executeUpdate();
        }
    }

    void delete(int id) throws IOException, SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM runs WHERE id = ?")) {
            statement.setInt(1, id);
            statement.executeUpdate();
        }
    }

    void checkpoint(Simulation.Session session) throws IOException {
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "session-", ".tmp");
        try {
            Files.write(temporary, encode(session));
            try {
                Files.move(temporary, directory.resolve("session.dat"),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, directory.resolve("session.dat"), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    Simulation restore() throws IOException, ClassNotFoundException {
        return new Simulation(decode(Files.readAllBytes(directory.resolve("session.dat"))));
    }

    private byte[] encode(Simulation.Session session) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(session);
        }
        return bytes.toByteArray();
    }

    private Simulation.Session decode(byte[] bytes) throws IOException, ClassNotFoundException {
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            input.setObjectInputFilter(ObjectInputFilter.Config.createFilter(
                    "maxdepth=12;maxrefs=1500;maxarray=200;maxbytes=65536;"
                            + "queuewise.Simulation$Session;queuewise.Simulation$Strategy;"
                            + "queuewise.Customer;queuewise.Customer$Priority;"
                            + "java.util.CollSer;java.util.ImmutableCollections$ListN;"
                            + "java.util.ImmutableCollections$List12;java.lang.Enum;java.lang.Object;!*"));
            return (Simulation.Session) input.readObject();
        }
    }

    static void export(Path path, Simulation simulation, boolean csv) throws IOException {
        Simulation.Summary result = simulation.summary();
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            if (csv) {
                writer.write("id,priority,arrival,service,counter,start,end,wait");
            } else {
                writer.write("QueueWise — " + simulation.snapshot().preset() + " / " + simulation.snapshot().strategy());
                writer.newLine();
                writer.write(String.format("Served %d | Avg wait %.2f min | Max wait %d min | Avg queue %.2f | "
                                + "Max queue %d | Turnaround %.2f min | Utilization %.1f%% | Duration %d min",
                        result.served(), result.averageWait(), result.maxWait(), result.averageQueue(),
                        result.maxQueue(), result.turnaround(), result.utilization(), result.duration()));
                writer.newLine();
            }
            for (Simulation.Visit visit : simulation.completed()) {
                Customer customer = visit.customer();
                writer.newLine();
                if (csv) {
                    writer.write(String.format("%d,%s,%d,%d,%d,%d,%d,%d", customer.id(), customer.priority(),
                            customer.arrival(), customer.service(), visit.counter(), visit.start(), visit.end(),
                            visit.waitMinutes()));
                } else {
                    writer.write(String.format("Customer %d (%s) arrived %d, served at counter %d from %d to %d, waited %d min",
                            customer.id(), customer.priority(), customer.arrival(), visit.counter(), visit.start(),
                            visit.end(), visit.waitMinutes()));
                }
            }
            writer.newLine();
        }
    }
}
