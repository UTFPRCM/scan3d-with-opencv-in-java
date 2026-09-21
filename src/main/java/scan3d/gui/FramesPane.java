package scan3d.gui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * Navegador dos quadros de um processamento: lista à esquerda (verde = 4 marcadores, laranja = 3, vermelho = ignorado)
 * e, à direita, a foto anotada com a pose ou, para quadros ignorados, a foto original e o motivo.
 */
public final class FramesPane extends SplitPane {

    enum Status {
        HIGH(Color.web("#3CB371"), "4 marcadores"), LOW(Color.web("#FFA000"), "3 marcadores (confiança menor)"),
        SKIPPED(Color.web("#D9534F"), "ignorado");

        final Color color;
        final String label;

        Status(Color color, String label) {
            this.color = color;
            this.label = label;
        }
    }

    record Item(String file, Status status, String detail, Path image) {}

    private final ObservableList<Item> all = FXCollections.observableArrayList();
    private final FilteredList<Item> filtered = new FilteredList<>(all);
    private final ListView<Item> list = new ListView<>(filtered);
    private final ImageView imageView = new ImageView();
    private final Label detail = new Label("Nenhum processamento carregado.");
    private final ChoiceBox<String> filter = new ChoiceBox<>();
    private final Label count = new Label();
    private Path inputDir;

    public FramesPane() {
        filter.getItems().addAll("Todos", "Confiança normal (4)", "Confiança menor (3)", "Ignorados");
        filter.setValue("Todos");
        filter.valueProperty().addListener((o, a, b) -> applyFilter());

        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Item it, boolean empty) {
                super.updateItem(it, empty);
                if (empty || it == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                setGraphic(new Circle(5, it.status().color));
                setText(it.file());
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, a, it) -> show(it));

        HBox top = new HBox(8, new Label("Mostrar:"), filter, count);
        top.setPadding(new Insets(6));
        BorderPane left = new BorderPane(list);
        left.setTop(top);

        imageView.setPreserveRatio(true);
        StackPane imageHolder = new StackPane(imageView);
        imageHolder.setStyle("-fx-background-color: #1E1E24;");
        imageView.fitWidthProperty().bind(imageHolder.widthProperty().subtract(8));
        imageView.fitHeightProperty().bind(imageHolder.heightProperty().subtract(8));
        detail.setWrapText(true);
        detail.setPadding(new Insets(8));
        VBox right = new VBox(imageHolder, detail);
        VBox.setVgrow(imageHolder, javafx.scene.layout.Priority.ALWAYS);

        getItems().addAll(left, right);
        setDividerPositions(0.3);
    }

    /** Lê poses.csv e skipped.txt da pasta de saída; {@code inputDir} (opcional) permite mostrar as fotos ignoradas. */
    public void load(Path outDir, Path inputDir) throws IOException {
        this.inputDir = inputDir;
        List<Item> items = new ArrayList<>();

        Path poses = outDir.resolve("poses.csv");
        if (Files.exists(poses)) {
            List<String> lines = Files.readAllLines(poses);
            for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
                List<String> c = splitCsv(line);
                if (c.size() < 15) continue; // colunas: veja o cabeçalho de poses.csv
                String file = c.get(1);
                boolean low = c.get(2).equals("3");
                String base = file.substring(0, file.lastIndexOf('.'));
                // 0 frame, 1 file, 2 markers, 4-6 tx ty tz, 7-9 rx ry rz, 10 reproj, 11 ref_frame, 12 ref_hops, 13 ref_dist, 14 points
                String confidence = low
                        ? "Pose recuperada a partir do quadro " + c.get(11) + " (salto " + c.get(12) + "), a " + c.get(13)
                                + " mm da referência. Com 3 marcadores não há erro de reprojeção para conferir."
                        : "Erro de reprojeção: " + c.get(10) + " px";
                String text = String.format("Quadro %s | %s%n%ncâmera (mm): tx %s, ty %s, tz %s%nângulos (°): rx %s, ry %s, rz %s%n%s%nPontos da nuvem neste quadro: %s",
                        c.get(0), low ? Status.LOW.label : Status.HIGH.label, c.get(4), c.get(5), c.get(6), c.get(7),
                        c.get(8), c.get(9), confidence, c.get(14));
                items.add(new Item(file, low ? Status.LOW : Status.HIGH, text,
                        outDir.resolve("annotated").resolve(base + ".jpg")));
            }
        }
        Path skipped = outDir.resolve("skipped.txt");
        if (Files.exists(skipped)) {
            for (String line : Files.readAllLines(skipped)) {
                int k = line.indexOf(": ");
                if (k < 0) continue;
                String file = line.substring(0, k);
                Path original = inputDir != null ? inputDir.resolve(file) : null;
                items.add(new Item(file, Status.SKIPPED, "Ignorado: " + line.substring(k + 2), original));
            }
        }
        items.sort(Comparator.comparing(Item::file));
        all.setAll(items);
        applyFilter();
        if (!filtered.isEmpty()) list.getSelectionModel().select(0);
    }

    private void applyFilter() {
        String f = filter.getValue();
        filtered.setPredicate(it -> switch (f) {
            case "Confiança normal (4)" -> it.status() == Status.HIGH;
            case "Confiança menor (3)" -> it.status() == Status.LOW;
            case "Ignorados" -> it.status() == Status.SKIPPED;
            default -> true;
        });
        count.setText(filtered.size() + " de " + all.size());
    }

    private void show(Item it) {
        if (it == null) return;
        detail.setText(it.detail());
        if (it.image() != null && Files.exists(it.image())) {
            imageView.setImage(new Image(it.image().toUri().toString(), true));
        } else {
            imageView.setImage(null);
        }
    }

    /** Divide uma linha de CSV respeitando aspas duplas. */
    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    sb.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(ch);
            }
        }
        out.add(sb.toString());
        return out;
    }
}
