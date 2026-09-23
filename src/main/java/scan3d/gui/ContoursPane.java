package scan3d.gui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * Navegador dos contornos do objeto (uma imagem por quadro, em {@code contours/}). Mostra o contorno sozinho ou
 * sobreposto à foto, para conferir se ele está sobre o objeto e ver o que entrou junto (borda da folha, rótulo etc.).
 */
public final class ContoursPane extends SplitPane {

    record Item(String file, boolean low, int points, Path contour, Path photo) {}

    private static final String MODE_OVERLAY = "Sobre a foto";
    private static final String MODE_ONLY = "Só o contorno";

    private final ObservableList<Item> all = FXCollections.observableArrayList();
    private final FilteredList<Item> filtered = new FilteredList<>(all);
    private final ListView<Item> list = new ListView<>(filtered);
    private final ImageView imageView = new ImageView();
    private final Label detail = new Label("Nenhum processamento carregado.");
    private final ChoiceBox<String> filter = new ChoiceBox<>();
    private final ChoiceBox<String> mode = new ChoiceBox<>();
    private final Label count = new Label();

    public ContoursPane() {
        filter.getItems().addAll("Todos", "Confiança normal (4)", "Confiança menor (3)");
        filter.setValue("Todos");
        filter.valueProperty().addListener((o, a, b) -> applyFilter());
        mode.getItems().addAll(MODE_OVERLAY, MODE_ONLY);
        mode.setValue(MODE_OVERLAY);
        mode.valueProperty().addListener((o, a, b) -> show(list.getSelectionModel().getSelectedItem()));

        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Item it, boolean empty) {
                super.updateItem(it, empty);
                if (empty || it == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                setGraphic(new Circle(5, it.low() ? Color.web("#FFA000") : Color.web("#3CB371")));
                setText(it.file() + "  (" + it.points() + " px)");
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, a, it) -> show(it));

        HBox top = new HBox(8, new Label("Mostrar:"), filter, count);
        top.setPadding(new Insets(6));
        BorderPane left = new BorderPane(list);
        left.setTop(top);

        HBox modeBar = new HBox(8, new Label("Exibir:"), mode);
        modeBar.setPadding(new Insets(6));
        imageView.setPreserveRatio(true);
        StackPane imageHolder = new StackPane(imageView);
        imageHolder.setStyle("-fx-background-color: #1E1E24;");
        imageView.fitWidthProperty().bind(imageHolder.widthProperty().subtract(8));
        imageView.fitHeightProperty().bind(imageHolder.heightProperty().subtract(8));
        detail.setWrapText(true);
        detail.setPadding(new Insets(8));
        VBox right = new VBox(modeBar, imageHolder, detail);
        VBox.setVgrow(imageHolder, Priority.ALWAYS);

        getItems().addAll(left, right);
        setDividerPositions(0.3);
    }

    /**
     * Lê {@code poses.csv} (quadros com pose, cada um com seu contorno em {@code contours/}).
     * A foto original vem de {@code inputDir}, se existir; sem ela, usa a foto anotada da saída.
     */
    public void load(Path outDir, Path inputDir) throws IOException {
        List<Item> items = new ArrayList<>();
        Path poses = outDir.resolve("poses.csv");
        if (Files.exists(poses)) {
            List<String> lines = Files.readAllLines(poses);
            for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
                List<String> c = FramesPane.splitCsv(line);
                if (c.size() < 16) continue; // colunas: veja o cabeçalho de poses.csv
                String file = c.get(1);
                String base = file.substring(0, file.lastIndexOf('.'));
                Path contour = outDir.resolve("contours").resolve(base + ".png");
                if (!Files.exists(contour)) continue;
                Path original = inputDir != null ? inputDir.resolve(file) : null;
                Path photo = original != null && Files.exists(original) ? original
                        : outDir.resolve("annotated").resolve(base + ".jpg");
                items.add(new Item(file, c.get(2).equals("3"), Integer.parseInt(c.get(15)), contour, photo));
            }
        }
        items.sort(java.util.Comparator.comparing(Item::file));
        all.setAll(items);
        applyFilter();
        if (!filtered.isEmpty()) list.getSelectionModel().select(0);
    }

    private void applyFilter() {
        String f = filter.getValue();
        filtered.setPredicate(it -> switch (f) {
            case "Confiança normal (4)" -> !it.low();
            case "Confiança menor (3)" -> it.low();
            default -> true;
        });
        count.setText(filtered.size() + " de " + all.size());
    }

    private void show(Item it) {
        if (it == null) return;
        detail.setText(String.format("%s%n%,d pixels de contorno | pose com %s marcadores%s", it.file(), it.points(),
                it.low() ? "3 (confiança menor)" : "4",
                mode.getValue().equals(MODE_OVERLAY) ? " | contorno em vermelho sobre a foto (escurecida)" : ""));
        Image contour = new Image(it.contour().toUri().toString());
        if (mode.getValue().equals(MODE_ONLY) || !Files.exists(it.photo())) {
            imageView.setImage(contour);
            return;
        }
        imageView.setImage(overlay(new Image(it.photo().toUri().toString()), contour));
    }

    /** Foto escurecida com os pixels do contorno em vermelho (engrossados 1 px para não sumir ao reduzir a imagem). */
    private static Image overlay(Image photo, Image contour) {
        int w = (int) photo.getWidth(), h = (int) photo.getHeight();
        if ((int) contour.getWidth() != w || (int) contour.getHeight() != h) return contour;
        PixelReader pr = photo.getPixelReader(), cr = contour.getPixelReader();
        WritableImage out = new WritableImage(w, h);
        PixelWriter pw = out.getPixelWriter();
        int[] dim = new int[w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = pr.getArgb(x, y);
                int r = (int) (((p >> 16) & 255) * 0.55), g = (int) (((p >> 8) & 255) * 0.55), b = (int) ((p & 255) * 0.55);
                dim[x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            pw.setPixels(0, y, w, 1, javafx.scene.image.PixelFormat.getIntArgbInstance(), dim, 0, w);
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (((cr.getArgb(x, y) >> 8) & 255) > 127) {
                    pw.setArgb(x, y, 0xFFFF3B30);
                    if (x + 1 < w) pw.setArgb(x + 1, y, 0xFFFF3B30);
                    if (y + 1 < h) pw.setArgb(x, y + 1, 0xFFFF3B30);
                }
            }
        }
        return out;
    }
}
