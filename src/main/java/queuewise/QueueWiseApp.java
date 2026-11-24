package queuewise;

import javafx.animation.*;
import javafx.application.Application;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class QueueWiseApp extends Application {
    private static final Logger LOG = Logger.getLogger(QueueWiseApp.class.getName());
    private final RunStore store = new RunStore(Path.of("data"));
    private final Properties defaults = new Properties();
    private final List<Customer> manualCustomers = new ArrayList<>();
    private final BorderPane shell = new BorderPane();
    private final VBox page = new VBox(20);
    private final Label status = new Label();
    private final VBox liveCounters = new VBox(9);
    private final Label liveClock = new Label();
    private final Label liveQueue = new Label();
    private final Label liveServed = new Label();
    private final ComboBox<Workload.Preset> preset = options(Workload.Preset.values());
    private final ComboBox<Simulation.Strategy> strategy = options(Simulation.Strategy.values());
    private final ComboBox<String> arrivals = options("Random intervals", "Peak hours", "Manual arrivals");
    private final TextField count = new TextField();
    private final TextField counters = new TextField();
    private final TextField minimum = new TextField();
    private final TextField maximum = new TextField();
    private final TextField seed = new TextField();
    private final ToggleGroup navigation = new ToggleGroup();
    private Stage stage;
    private ScrollPane scroll;
    private Timeline playback;
    private Timeline scrollMotion;
    private double scrollTarget;
    private Simulation simulation;
    private FileHandler fileLog;
    private ListView<Customer> manualList;

    @Override
    public void start(Stage primaryStage) throws Exception {
        stage = primaryStage;
        try (InputStream input = getClass().getResourceAsStream("/config.properties")) {
            defaults.load(input);
        }
        try {
            Files.createDirectories(Path.of("data"));
            fileLog = new FileHandler("data/queuewise.log", true);
            fileLog.setFormatter(new SimpleFormatter());
            LOG.addHandler(fileLog);
        } catch (Exception error) {
            LOG.warning(error.getMessage());
        }
        playback = new Timeline(new KeyFrame(Duration.millis(
                Integer.parseInt(defaults.getProperty("liveIntervalMs"))), event -> advance()));
        playback.setCycleCount(Animation.INDEFINITE);
        preset.setOnAction(event -> applyPreset());
        applyPreset();
        arrivals.setOnAction(event -> updateArrivalControls());

        Label brand = styledLabel("QueueWise", "brand");
        VBox sidebar = new VBox(12, brand, nav("Overview", this::overview), nav("Setup", this::setup),
                nav("Live", this::live), nav("Results", this::results),
                nav("Staffing", this::staffing), nav("History", this::history));
        sidebar.getStyleClass().add("sidebar");
        scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.addEventFilter(ScrollEvent.SCROLL, this::easeScroll);
        status.getStyleClass().add("status");
        status.setWrapText(true);
        shell.setLeft(sidebar);
        shell.setCenter(scroll);
        shell.setBottom(status);
        stage.setTitle("QueueWise");
        Scene scene = new Scene(shell, 1200, 790);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        stage.setMinWidth(880);
        stage.setMinHeight(640);
        stage.setScene(scene);
        stage.show();
        overview();
    }

    @Override
    public void stop() {
        if (fileLog != null) {
            LOG.removeHandler(fileLog);
            fileLog.close();
        }
    }

    private void easeScroll(ScrollEvent event) {
        if (event.isDirect() || event.getDeltaY() == 0 || event.isControlDown()) {
            return;
        }
        for (Node node = event.getTarget() instanceof Node target ? target : null;
             node != null && node != scroll; node = node.getParent()) {
            if (node instanceof Control && !(node instanceof ScrollPane)) {
                return;
            }
        }
        double overflow = page.getHeight() - scroll.getViewportBounds().getHeight();
        if (overflow <= 0) {
            return;
        }
        if (scrollMotion == null || scrollMotion.getStatus() != Animation.Status.RUNNING) {
            scrollTarget = scroll.getVvalue();
        } else {
            scrollMotion.stop();
        }
        scrollTarget = Math.clamp(scrollTarget - event.getDeltaY() / overflow, 0, 1);
        scrollMotion = new Timeline(new KeyFrame(Duration.millis(160),
                new KeyValue(scroll.vvalueProperty(), scrollTarget, Interpolator.EASE_OUT)));
        scrollMotion.play();
        event.consume();
    }

    private ToggleButton nav(String name, Runnable action) {
        ToggleButton button = new ToggleButton(name);
        button.setToggleGroup(navigation);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setOnAction(event -> action.run());
        return button;
    }

    private void screen(String name, String title, String description, Node... content) {
        playback.pause();
        if (scrollMotion != null) {
            scrollMotion.stop();
        }
        page.getChildren().setAll(styledLabel(title, "title"), styledLabel(description, "muted"));
        page.getChildren().addAll(content);
        scroll.setVvalue(0);
        for (Toggle toggle : navigation.getToggles()) {
            if (((ToggleButton) toggle).getText().equals(name)) {
                navigation.selectToggle(toggle);
                break;
            }
        }
        status.setText("");
    }

    private void overview() {
        HBox presets = new HBox(14);
        for (Workload.Preset choice : Workload.Preset.values()) {
            Button open = button("Configure " + choice.label, () -> {
                preset.setValue(choice);
                setup();
            });
            VBox card = panel(choice.label, styledLabel(choice.customers + " customers", "metric"),
                    new Label(choice.counters + " counters  /  " + choice.minimum + "–" + choice.maximum + " min service"), open);
            card.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(card, Priority.ALWAYS);
            presets.getChildren().add(card);
        }
        screen("Overview", "Queue simulator", "Compare service strategies and see how staffing changes wait times.",
                presets, panel("Simulation workspace",
                        new Label("Configure a workload, then watch it live or calculate the results immediately."),
                        actions(primary("New simulation", this::setup), button("Restore session", () -> {
                            simulation = store.restore();
                            live();
                            status.setText("Session restored. Press Start to continue.");
                        }))));
    }

    private void applyPreset() {
        Workload.Preset selected = preset.getValue();
        count.setText(Integer.toString(selected.customers));
        counters.setText(Integer.toString(selected.counters));
        minimum.setText(Integer.toString(selected.minimum));
        maximum.setText(Integer.toString(selected.maximum));
        seed.setText(defaults.getProperty("seed", "42"));
    }

    private void setup() {
        TextField arrival = new TextField("0");
        TextField service = new TextField("3");
        ComboBox<Customer.Priority> priority = options(Customer.Priority.values());
        priority.setValue(Customer.Priority.NORMAL);
        manualList = new ListView<>();
        manualList.getItems().setAll(manualCustomers);
        manualList.setPrefHeight(140);
        manualList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Customer customer, boolean empty) {
                super.updateItem(customer, empty);
                setText(empty ? null : "Customer " + customer.id() + "  ·  arrival " + customer.arrival()
                        + "  ·  service " + customer.service() + " min  ·  " + customer.priority());
            }
        });
        VBox manual = panel("Manual workload",
                actions(field("Arrival minute", arrival), field("Service minutes", service), field("Priority", priority)),
                actions(button("Add customer", () -> {
                    if (manualCustomers.size() == 200) {
                        throw new InvalidInputException("The manual workload is limited to 200 customers.");
                    }
                    int id = manualCustomers.isEmpty() ? 1 : manualCustomers.getLast().id() + 1;
                    manualCustomers.add(new Customer(id, number(arrival, "Arrival"), number(service, "Service"),
                            priority.getValue()));
                    manualList.getItems().setAll(manualCustomers);
                }), button("Remove selected", () -> {
                    manualCustomers.remove(manualList.getSelectionModel().getSelectedItem());
                    manualList.getItems().setAll(manualCustomers);
                })), manualList);
        manual.visibleProperty().bind(arrivals.valueProperty().isEqualTo("Manual arrivals"));
        manual.managedProperty().bind(manual.visibleProperty());
        // These controls are reused when returning to setup. Detach their previous form rows.
        for (Node node : List.of(preset, strategy, arrivals, count, counters, minimum, maximum, seed)) {
            if (node.getParent() instanceof Pane parent) {
                parent.getChildren().remove(node);
            }
        }
        VBox form = panel("Workload settings",
                actions(field("Environment", preset), field("Queue strategy", strategy), field("Arrival pattern", arrivals)),
                actions(field("Customers", count), field("Counters", counters), field("Seed", seed)),
                actions(field("Minimum service (min)", minimum), field("Maximum service (min)", maximum)));
        updateArrivalControls();
        screen("Setup", "Simulation setup", "Choose how customers arrive and how counters serve them.",
                form, manual, actions(primary("Run live", () -> {
                    simulation = new Simulation(config());
                    live();
                    playback.play();
                }), button("Calculate instantly", () -> {
                    simulation = new Simulation(config());
                    simulation.runToEnd();
                    LOG.info("Instant simulation completed.");
                    results();
                })));
    }

    private void updateArrivalControls() {
        boolean manual = "Manual arrivals".equals(arrivals.getValue());
        for (TextField field : List.of(count, minimum, maximum, seed)) {
            field.setDisable(manual);
        }
    }

    private Simulation.Session config() {
        List<Customer> customers = manualCustomers;
        if (!"Manual arrivals".equals(arrivals.getValue())) {
            customers = Workload.generate(number(count, "Customers"), number(minimum, "Minimum service"),
                    number(maximum, "Maximum service"), "Peak hours".equals(arrivals.getValue()),
                    Long.parseLong(seed.getText().trim()));
        }
        return new Simulation.Session(preset.getValue().label, customers, number(counters, "Counters"),
                strategy.getValue(), 0);
    }

    private void live() {
        if (simulation == null) {
            empty("Live", "Live simulation", "Configure a workload to start.");
            return;
        }
        Slider speed = new Slider(0.5, 4, playback.getRate());
        speed.valueProperty().addListener((value, before, after) -> playback.setRate(after.doubleValue()));
        liveClock.getStyleClass().setAll("label", "metric");
        liveQueue.setWrapText(true);
        for (Node node : List.of(liveClock, liveServed, liveQueue, liveCounters)) {
            if (node.getParent() instanceof Pane parent) {
                parent.getChildren().remove(node);
            }
        }
        screen("Live", "Live simulation", simulation.snapshot().preset() + "  /  " + simulation.snapshot().strategy(),
                panel("Simulation clock", liveClock, liveServed,
                        actions(primary("Start", () -> { if (!simulation.finished()) playback.play(); }),
                                button("Pause", playback::pause), button("Step", () -> {
                                    playback.pause();
                                    advance();
                                }), button("Reset", () -> {
                                    Simulation.Session session = simulation.snapshot();
                                    simulation = new Simulation(new Simulation.Session(session.preset(), session.customers(),
                                            session.counters(), session.strategy(), 0));
                                    live();
                                }), field("Playback speed", speed))),
                panel("Waiting queue", liveQueue), panel("Service counters", liveCounters),
                actions(button("Save session", () -> {
                    playback.pause();
                    if (simulation.finished()) {
                        throw new InvalidInputException("This run is complete. Save it from Results.");
                    }
                    store.checkpoint(simulation.snapshot());
                    status.setText("Session saved. Restore it from Overview.");
                }), button("View results", this::results)));
        renderLive();
    }

    private void advance() {
        simulation.step();
        renderLive();
        if (simulation.finished()) {
            playback.stop();
            status.setText("Simulation complete. Open Results to compare and save.");
            LOG.info("Live simulation completed.");
        }
    }

    private void renderLive() {
        liveClock.setText(String.format("%02d:%02d", simulation.clock() / 60, simulation.clock() % 60));
        liveServed.setText(simulation.completed().size() + " served of " + simulation.snapshot().customers().size());
        List<String> waiting = new ArrayList<>();
        for (Customer customer : simulation.waiting()) {
            waiting.add("#" + customer.id() + " " + customer.priority());
        }
        liveQueue.setText(waiting.isEmpty() ? "No customers waiting" : String.join("     ", waiting));
        liveCounters.getChildren().clear();
        for (int index = 0; index < simulation.snapshot().counters(); index++) {
            Simulation.Visit visit = simulation.counter(index);
            String state = "Available";
            double progress = 0;
            if (visit != null) {
                state = "Serving #" + visit.customer().id();
                progress = (simulation.clock() - visit.start()) / (double) visit.customer().service();
            }
            ProgressBar bar = new ProgressBar(progress);
            bar.setMaxWidth(Double.MAX_VALUE);
            HBox row = actions(new Label("Counter " + (index + 1)), bar, new Label(state));
            HBox.setHgrow(bar, Priority.ALWAYS);
            liveCounters.getChildren().add(row);
        }
    }

    private void results() {
        if (simulation == null || !simulation.finished()) {
            screen("Results", "Simulation results", "Complete a run to view results.",
                    actions(button("Open setup", this::setup), button("Open live view", this::live)));
            return;
        }
        Simulation.Summary summary = simulation.summary();
        HBox metrics = actions(metric("Average wait", decimal(summary.averageWait()) + " min"),
                metric("Peak queue", Integer.toString(summary.maxQueue())),
                metric("Counter utilization", decimal(summary.utilization()) + "%"),
                metric("Customers served", Integer.toString(summary.served())));
        List<Integer> lengths = simulation.queueLengths();
        LineChart<Number, Number> chart = lineChart("Simulated minute", "Waiting customers");
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        int sampleSize = Math.max(1, (lengths.size() + 599) / 600);
        for (int start = 0; start < lengths.size(); start += sampleSize) {
            int peak = 0;
            for (int index = start; index < Math.min(start + sampleSize, lengths.size()); index++) {
                peak = Math.max(peak, lengths.get(index));
            }
            series.getData().add(new XYChart.Data<>(start, peak));
        }
        chart.getData().add(series);
        TableView<Simulation.Visit> table = visitsTable();
        screen("Results", "Simulation results",
                simulation.snapshot().preset() + "  /  " + simulation.snapshot().strategy(),
                metrics,
                new FlowPane(10, 10, button("Compare FIFO and Priority", this::compare), button("Find counter count", this::staffing),
                        primary("Save to history", () -> {
                            String suggested = simulation.snapshot().preset() + " · " + simulation.snapshot().strategy();
                            String name = runName("Save run", suggested);
                            if (name != null) {
                                store.save(name, simulation);
                                status.setText("Saved to history.");
                            }
                        }), button("Export TXT", () -> export(false)), button("Export CSV", () -> export(true))),
                panel(sampleSize == 1 ? "Queue length over time" : "Peak queue per " + sampleSize + " minutes", chart),
                panel("Run details", new Label("Maximum wait " + summary.maxWait() + " min  ·  Average queue "
                        + decimal(summary.averageQueue()) + "  ·  Average turnaround " + decimal(summary.turnaround())
                        + " min  ·  Duration " + summary.duration() + " min"), table));
    }

    private void compare() {
        Simulation.Session workload = simulation.snapshot();
        Simulation fifo = Workload.run(workload, workload.counters(), Simulation.Strategy.FIFO);
        Simulation priority = Workload.run(workload, workload.counters(), Simulation.Strategy.PRIORITY);
        CategoryAxis categories = new CategoryAxis();
        NumberAxis minutes = new NumberAxis();
        minutes.setLabel("Average wait (min)");
        BarChart<String, Number> chart = new BarChart<>(categories, minutes);
        chart.setAnimated(false);
        chart.setPrefHeight(320);
        chart.getData().add(waitSeries(fifo, "FIFO"));
        chart.getData().add(waitSeries(priority, "Priority"));
        screen("Results", "Strategy comparison", "Both strategies serve the same customers with " + workload.counters() + " counters.",
                panel("Wait by priority", chart),
                panel("Overall metrics", comparisonRow("FIFO", fifo.summary()),
                        comparisonRow("Priority", priority.summary()),
                        new Label("Priority can shorten high-priority waits while increasing waits for other customers.")),
                button("Back to run", this::results));
    }

    private static XYChart.Series<String, Number> waitSeries(Simulation run, String name) {
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        series.setName(name);
        for (Customer.Priority priority : Customer.Priority.values()) {
            int total = 0;
            int count = 0;
            for (Simulation.Visit visit : run.completed()) {
                if (visit.customer().priority() == priority) {
                    total += visit.waitMinutes();
                    count++;
                }
            }
            if (count > 0) {
                series.getData().add(new XYChart.Data<>(priority.name(), total / (double) count));
            }
        }
        return series;
    }

    private static Label comparisonRow(String strategy, Simulation.Summary summary) {
        Label row = new Label(strategy + "  ·  Avg wait " + decimal(summary.averageWait()) + " min  ·  Max wait "
                + summary.maxWait() + " min  ·  Served " + summary.served() + "  ·  Utilization "
                + decimal(summary.utilization()) + "%\nAverage queue " + decimal(summary.averageQueue())
                + "  ·  Peak queue " + summary.maxQueue() + "  ·  Turnaround " + decimal(summary.turnaround())
                + " min  ·  Duration " + summary.duration() + " min");
        row.setWrapText(true);
        return row;
    }

    private void staffing() {
        if (simulation == null) {
            empty("Staffing", "Staffing analysis", "Configure a workload before testing counter counts.");
            return;
        }
        TextField target = new TextField(defaults.getProperty("targetWait"));
        Label recommendation = styledLabel("", "metric");
        LineChart<Number, Number> chart = lineChart("Counters", "Average wait (min)");
        NumberAxis countersAxis = (NumberAxis) chart.getXAxis();
        countersAxis.setAutoRanging(false);
        countersAxis.setLowerBound(1);
        countersAxis.setUpperBound(10);
        countersAxis.setTickUnit(1);
        VBox rows = new VBox(8);
        Simulation.Session workload = simulation.snapshot();
        Button calculate = primary("Calculate recommendation", () -> {
            double threshold = Double.parseDouble(target.getText().trim());
            if (!Double.isFinite(threshold) || threshold < 0) {
                throw new InvalidInputException("The wait target must be a nonnegative number.");
            }
            XYChart.Series<Number, Number> waits = new XYChart.Series<>();
            XYChart.Series<Number, Number> goal = new XYChart.Series<>();
            goal.getData().addAll(List.of(new XYChart.Data<>(1, threshold), new XYChart.Data<>(10, threshold)));
            int recommended = 0;
            rows.getChildren().clear();
            for (int index = 1; index <= 10; index++) {
                Simulation.Summary result = Workload.run(workload, index, workload.strategy()).summary();
                waits.getData().add(new XYChart.Data<>(index, result.averageWait()));
                rows.getChildren().add(new Label(index + (index == 1 ? " counter" : " counters") + "   ·   "
                        + decimal(result.averageWait()) + " min wait   ·   " + decimal(result.utilization()) + "% utilization"));
                if (recommended == 0 && result.averageWait() <= threshold) {
                    recommended = index;
                }
            }
            recommendation.setText(recommended == 0 ? "No tested count meets the target"
                    : recommended + (recommended == 1 ? " counter" : " counters") + " recommended");
            chart.getData().setAll(List.of(waits, goal));
        });
        screen("Staffing", "Staffing analysis", workload.preset() + "  /  " + workload.strategy()
                        + "  ·  Tests 1–10 counters on the same workload",
                panel("Waiting time target", actions(field("Average wait target (min)", target), calculate), recommendation),
                panel("Average wait by counter count", chart, rows));
        calculate.fire();
    }

    private void history() {
        TextField filter = new TextField();
        filter.setPromptText("Search names, environments, or strategies");
        ListView<RunStore.SavedRun> list = new ListView<>();
        list.setPrefHeight(330);
        screen("History", "Run history", "Review completed simulations saved on this computer.",
                panel("Saved runs", filter, list, actions(primary("Open", () -> {
                    simulation = store.open(selected(list).id());
                    results();
                }), button("Rename", () -> {
                    RunStore.SavedRun run = selected(list);
                    String name = runName("Rename run", run.name());
                    if (name != null) {
                        store.rename(run.id(), name);
                        history();
                    }
                }), button("Delete", () -> {
                    RunStore.SavedRun run = selected(list);
                    Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION, "Delete “" + run.name() + "”?");
                    confirmation.initOwner(stage);
                    if (confirmation.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
                        store.delete(run.id());
                        history();
                    }
                }))));
        checked(() -> {
            List<RunStore.SavedRun> items = store.list();
            filter.textProperty().addListener((property, before, after) -> {
                list.getItems().clear();
                for (RunStore.SavedRun item : items) {
                    if (item.toString().toLowerCase(Locale.ROOT).contains(after.toLowerCase(Locale.ROOT))) {
                        list.getItems().add(item);
                    }
                }
            });
            list.getItems().setAll(items);
            list.setPlaceholder(new Label("No saved runs match."));
        });
    }

    private static RunStore.SavedRun selected(ListView<RunStore.SavedRun> list) {
        RunStore.SavedRun selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) {
            throw new InvalidInputException("Select a saved run first.");
        }
        return selected;
    }

    private String runName(String title, String current) {
        TextInputDialog dialog = new TextInputDialog(current);
        dialog.initOwner(stage);
        dialog.setTitle(title);
        dialog.setHeaderText(title);
        String name = dialog.showAndWait().orElse("").trim();
        return name.isEmpty() ? null : name;
    }

    private void export(boolean csv) throws Exception {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export report");
        String extension = csv ? ".csv" : ".txt";
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(csv ? "CSV" : "Text", "*" + extension));
        chooser.setInitialFileName("queuewise-report" + extension);
        var file = chooser.showSaveDialog(stage);
        if (file != null) {
            RunStore.export(file.toPath(), simulation, csv);
            status.setText("Report saved to " + file.getName());
        }
    }

    private TableView<Simulation.Visit> visitsTable() {
        TableView<Simulation.Visit> table = new TableView<>();
        table.setPrefHeight(290);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        addColumn(table, "Customer", visit -> "#" + visit.customer().id());
        addColumn(table, "Priority", visit -> visit.customer().priority().name());
        addColumn(table, "Arrival", visit -> Integer.toString(visit.customer().arrival()));
        addColumn(table, "Counter", visit -> Integer.toString(visit.counter()));
        addColumn(table, "Start", visit -> Integer.toString(visit.start()));
        addColumn(table, "End", visit -> Integer.toString(visit.end()));
        addColumn(table, "Wait (min)", visit -> Integer.toString(visit.waitMinutes()));
        table.getItems().setAll(simulation.completed());
        return table;
    }

    private static void addColumn(TableView<Simulation.Visit> table, String title,
                                  java.util.function.Function<Simulation.Visit, String> value) {
        TableColumn<Simulation.Visit, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        table.getColumns().add(column);
    }

    private static LineChart<Number, Number> lineChart(String horizontal, String vertical) {
        NumberAxis x = new NumberAxis();
        NumberAxis y = new NumberAxis();
        x.setLabel(horizontal);
        y.setLabel(vertical);
        LineChart<Number, Number> chart = new LineChart<>(x, y);
        chart.setAnimated(false);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(false);
        chart.setPrefHeight(280);
        return chart;
    }

    private void empty(String name, String title, String description) {
        screen(name, title, description, primary("Open setup", this::setup));
    }

    private static VBox panel(String title, Node... children) {
        VBox box = new VBox(11, styledLabel(title, "section"));
        box.getStyleClass().add("glass");
        box.getChildren().addAll(children);
        return box;
    }

    private static VBox metric(String title, String value) {
        VBox box = panel(title, styledLabel(value, "metric"));
        box.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private static HBox actions(Node... children) {
        HBox row = new HBox(10, children);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static VBox field(String title, Control control) {
        Label label = styledLabel(title, "muted");
        label.setLabelFor(control);
        return new VBox(5, label, control);
    }

    private static Label styledLabel(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        return label;
    }

    @SafeVarargs
    private static <T> ComboBox<T> options(T... values) {
        ComboBox<T> box = new ComboBox<>();
        box.getItems().setAll(values);
        box.getSelectionModel().selectFirst();
        return box;
    }

    private static int number(TextField input, String name) {
        try {
            return Integer.parseInt(input.getText().trim());
        } catch (NumberFormatException error) {
            throw new InvalidInputException(name + " must be a whole number.");
        }
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private Button primary(String label, Action action) {
        Button button = button(label, action);
        button.getStyleClass().add("primary");
        return button;
    }

    private Button button(String label, Action action) {
        Button button = new Button(label);
        button.setOnAction(event -> checked(action));
        return button;
    }

    private void checked(Action action) {
        status.setText("");
        try {
            action.run();
        } catch (NumberFormatException error) {
            status.setText("Enter a valid number.");
        } catch (java.nio.file.NoSuchFileException error) {
            status.setText("No saved session was found.");
        } catch (Exception error) {
            status.setText(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            LOG.warning(error.toString());
        }
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
