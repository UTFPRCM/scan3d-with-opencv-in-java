package scan3d.gui;

import java.util.Arrays;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import scan3d.PlyReader;

/**
 * Visualizador de nuvem de pontos em JavaFX puro, sem OpenGL.
 * <p>
 * Os pontos são projetados (perspectiva) para um buffer de pixels com z-buffer e o buffer é desenhado no Canvas,
 * o que aguenta algumas centenas de milhares de pontos. Eixos e textos são desenhados por cima como vetores.
 * Controles: arrastar = girar; botão direito, Shift+arrastar ou Alt+arrastar = mover; roda = zoom; duplo clique = reiniciar.
 */
public final class CloudView extends BorderPane {

    enum ColorMode {
        HEIGHT("Altura (Z)"), FRAME("Quadro (ordem da volta)"), CONFIDENCE("Confiança da pose"), SINGLE("Cor única");

        private final String label;

        ColorMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final int BACKGROUND = 0xFF1E1E24;
    private static final int COLOR_4 = 0xFF4C9BE8;     // 4 marcadores
    private static final int COLOR_3 = 0xFFFFA000;     // 3 marcadores (confiança menor)
    private static final int COLOR_SINGLE = 0xFFDDDDDD;
    /** Rampa de altura (baixo → alto), estilo viridis. */
    private static final int[] RAMP = {0x440154, 0x3B528B, 0x21918C, 0x5EC962, 0xFDE725};

    private final Canvas canvas = new Canvas();
    private final Pane holder = new Pane(canvas);
    private final Label info = new Label("Nenhuma nuvem carregada.");
    private final ChoiceBox<ColorMode> colorBox = new ChoiceBox<>();
    private final CheckBox lowBox = new CheckBox("Mostrar confiança menor (3 marcadores)");
    private final CheckBox clipBox = new CheckBox("Ocultar pontos extremos (1%)");
    private final Slider sizeSlider = new Slider(1, 6, 2);

    private PlyReader.Cloud cloud;
    private int[] visible = new int[0];
    private int[] colors = new int[0];
    private float[] loP = new float[3], hiP = new float[3];   // percentis 1 e 99 por eixo
    private double cx, cy, cz, radius = 1;                     // centro e raio de enquadramento

    private double yaw = -0.6, pitch = 0.55, zoom = 1, panX, panY;
    private double lastX, lastY;
    private WritableImage image;
    private int[] pixels = new int[0];
    private float[] depth = new float[0];

    // transformação corrente
    private double cosY, sinY, cosP, sinP, baseScale, camDist;
    private int viewW, viewH;

    public CloudView() {
        colorBox.getItems().addAll(ColorMode.values());
        colorBox.setValue(ColorMode.HEIGHT);
        lowBox.setSelected(true);
        clipBox.setSelected(true);
        sizeSlider.setPrefWidth(90);
        sizeSlider.setBlockIncrement(1);
        sizeSlider.setMajorTickUnit(1);
        sizeSlider.setSnapToTicks(true);
        Button reset = new Button("Reiniciar vista");
        reset.setOnAction(e -> resetView());

        HBox bar = new HBox(10, new Label("Cor:"), colorBox, lowBox, clipBox, new Label("Ponto:"), sizeSlider, reset);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 8, 6, 8));
        info.setPadding(new Insets(4, 8, 4, 8));

        setTop(bar);
        setCenter(holder);
        setBottom(info);

        canvas.widthProperty().bind(holder.widthProperty());
        canvas.heightProperty().bind(holder.heightProperty());
        canvas.widthProperty().addListener((o, a, b) -> render());
        canvas.heightProperty().addListener((o, a, b) -> render());

        colorBox.valueProperty().addListener((o, a, b) -> { recolor(); render(); });
        lowBox.selectedProperty().addListener((o, a, b) -> { refilter(); render(); });
        clipBox.selectedProperty().addListener((o, a, b) -> { refilter(); render(); });
        sizeSlider.valueProperty().addListener((o, a, b) -> render());

        canvas.setOnMousePressed(e -> {
            lastX = e.getX();
            lastY = e.getY();
        });
        canvas.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) resetView();
        });
        canvas.setOnMouseDragged(e -> {
            double dx = e.getX() - lastX, dy = e.getY() - lastY;
            lastX = e.getX();
            lastY = e.getY();
            if (e.getButton() == MouseButton.SECONDARY || e.isShiftDown() || e.isAltDown()) {
                panX += dx;
                panY += dy;
            } else {
                yaw += dx * 0.01;
                pitch = Math.max(-1.55, Math.min(1.55, pitch + dy * 0.01));
            }
            render();
        });
        canvas.setOnScroll(e -> {
            zoom = Math.max(0.1, Math.min(50, zoom * Math.exp(e.getDeltaY() * 0.002)));
            render();
        });
    }

    public void setCloud(PlyReader.Cloud c) {
        this.cloud = c;
        computeExtremes();
        refilter();
        resetView();
    }

    /** Vista inicial ou reiniciada: enquadra o miolo da nuvem (percentis 1-99) e restaura os ângulos. */
    public void resetView() {
        yaw = -0.6;
        pitch = 0.55;
        zoom = 1;
        panX = panY = 0;
        render();
    }

    /** Define os ângulos da vista, em graus (giro em torno de Z e elevação: 0 = de lado, 90 = de cima). */
    public void setView(double yawDeg, double pitchDeg) {
        yaw = Math.toRadians(yawDeg);
        pitch = Math.max(-1.55, Math.min(1.55, Math.toRadians(pitchDeg)));
        render();
    }

    /** Quadro atual da visualização, para gerar imagem de documentação. */
    public WritableImage snapshotCanvas() {
        render();
        return canvas.snapshot(null, null);
    }

    // ------------------------------------------------------------------ dados

    private void computeExtremes() {
        int n = cloud.size();
        float[][] axes = {cloud.x(), cloud.y(), cloud.z()};
        for (int a = 0; a < 3; a++) {
            if (n == 0) break;
            float[] s = axes[a].clone();
            Arrays.sort(s);
            loP[a] = s[(int) Math.floor(0.01 * (n - 1))];
            hiP[a] = s[(int) Math.ceil(0.99 * (n - 1))];
        }
    }

    /** Recalcula quais pontos são desenhados e o enquadramento. */
    private void refilter() {
        if (cloud == null) return;
        int n = cloud.size();
        int[] tmp = new int[n];
        int m = 0;
        boolean showLow = lowBox.isSelected(), clip = clipBox.isSelected();
        for (int i = 0; i < n; i++) {
            if (!showLow && cloud.markers()[i] < 4) continue;
            if (clip && (cloud.x()[i] < loP[0] || cloud.x()[i] > hiP[0] || cloud.y()[i] < loP[1] || cloud.y()[i] > hiP[1]
                    || cloud.z()[i] < loP[2] || cloud.z()[i] > hiP[2])) continue;
            tmp[m++] = i;
        }
        visible = Arrays.copyOf(tmp, m);

        // enquadramento pelo miolo (1-99%), assim poucos extremos não encolhem a nuvem
        cx = (loP[0] + hiP[0]) / 2;
        cy = (loP[1] + hiP[1]) / 2;
        cz = (loP[2] + hiP[2]) / 2;
        double dx = hiP[0] - loP[0], dy = hiP[1] - loP[1], dz = hiP[2] - loP[2];
        radius = Math.max(1, Math.sqrt(dx * dx + dy * dy + dz * dz) / 2);
        recolor();
    }

    private void recolor() {
        if (cloud == null) return;
        colors = new int[visible.length];
        ColorMode mode = colorBox.getValue();
        int fMin = Integer.MAX_VALUE, fMax = Integer.MIN_VALUE;
        for (int i : visible) {
            fMin = Math.min(fMin, cloud.frame()[i]);
            fMax = Math.max(fMax, cloud.frame()[i]);
        }
        double zLo = loP[2], zSpan = Math.max(1e-6, hiP[2] - loP[2]);
        for (int k = 0; k < visible.length; k++) {
            int i = visible[k];
            colors[k] = switch (mode) {
                case HEIGHT -> ramp((cloud.z()[i] - zLo) / zSpan);
                case FRAME -> hue(fMax == fMin ? 0 : (cloud.frame()[i] - fMin) / (double) (fMax - fMin));
                case CONFIDENCE -> cloud.markers()[i] >= 4 ? COLOR_4 : COLOR_3;
                case SINGLE -> COLOR_SINGLE;
            };
        }
    }

    private static int ramp(double t) {
        t = Math.max(0, Math.min(1, t)) * (RAMP.length - 1);
        int i = Math.min((int) t, RAMP.length - 2);
        double f = t - i;
        int a = RAMP[i], b = RAMP[i + 1];
        int r = (int) (((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = (int) (((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = (int) ((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static int hue(double t) {
        Color c = Color.hsb(t * 300, 0.75, 1.0);
        return 0xFF000000 | ((int) Math.round(c.getRed() * 255) << 16) | ((int) Math.round(c.getGreen() * 255) << 8)
                | (int) Math.round(c.getBlue() * 255);
    }

    // ------------------------------------------------------------------ desenho

    private void prepare() {
        cosY = Math.cos(yaw);
        sinY = Math.sin(yaw);
        cosP = Math.cos(pitch);
        sinP = Math.sin(pitch);
        baseScale = Math.min(viewW, viewH) / (2.2 * radius) * zoom;
        camDist = 3 * radius;
    }

    /** Projeta um ponto do mundo (Z para cima). out = {x da tela, y da tela, profundidade}; false se atrás da câmera. */
    private boolean project(double wx, double wy, double wz, double[] out) {
        double x = wx - cx, y = wy - cy, z = wz - cz;
        double x1 = cosY * x - sinY * y;
        double y1 = sinY * x + cosY * y;
        double up = y1 * sinP + z * cosP;
        double dpt = y1 * cosP - z * sinP;
        double den = camDist + dpt;
        if (den < 0.05 * camDist) return false;
        double sc = baseScale * camDist / den;
        out[0] = viewW / 2.0 + panX + sc * x1;
        out[1] = viewH / 2.0 + panY - sc * up;
        out[2] = dpt;
        return true;
    }

    private void render() {
        int w = (int) canvas.getWidth(), h = (int) canvas.getHeight();
        if (w < 10 || h < 10) return;
        viewW = w;
        viewH = h;
        if (image == null || (int) image.getWidth() != w || (int) image.getHeight() != h) {
            image = new WritableImage(w, h);
            pixels = new int[w * h];
            depth = new float[w * h];
        }
        Arrays.fill(pixels, BACKGROUND);
        Arrays.fill(depth, Float.MAX_VALUE);
        prepare();

        int size = (int) Math.round(sizeSlider.getValue());
        double[] p = new double[3];
        if (cloud != null) {
            for (int k = 0; k < visible.length; k++) {
                int i = visible[k];
                if (!project(cloud.x()[i], cloud.y()[i], cloud.z()[i], p)) continue;
                int sx = (int) Math.round(p[0]) - size / 2, sy = (int) Math.round(p[1]) - size / 2;
                float d = (float) p[2];
                for (int yy = Math.max(0, sy); yy < Math.min(h, sy + size); yy++) {
                    for (int xx = Math.max(0, sx); xx < Math.min(w, sx + size); xx++) {
                        int idx = yy * w + xx;
                        if (d < depth[idx]) {
                            depth[idx] = d;
                            pixels[idx] = colors[k];
                        }
                    }
                }
            }
        }
        image.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbPreInstance(), pixels, 0, w);

        GraphicsContext g = canvas.getGraphicsContext2D();
        g.drawImage(image, 0, 0);
        drawAxes(g);
        updateInfo();
    }

    /** Eixos na origem (X vermelho, Y verde, Z azul), com comprimento arredondado a ~30% do raio da nuvem. */
    private void drawAxes(GraphicsContext g) {
        double[] o = new double[3], e = new double[3];
        double len = niceLength(radius * 0.3);
        double[][] ends = {{len, 0, 0}, {0, len, 0}, {0, 0, len}};
        Color[] cols = {Color.web("#FF5555"), Color.web("#55DD55"), Color.web("#5599FF")};
        String[] names = {"X", "Y", "Z"};
        g.setFont(Font.font(12));
        g.setLineWidth(2);
        if (!project(0, 0, 0, o)) return;
        for (int a = 0; a < 3; a++) {
            if (!project(ends[a][0], ends[a][1], ends[a][2], e)) continue;
            g.setStroke(cols[a]);
            g.strokeLine(o[0], o[1], e[0], e[1]);
            g.setFill(cols[a]);
            g.fillText(names[a], e[0] + 3, e[1] - 3);
        }
        g.setFill(Color.web("#9A9AA5"));
        g.fillText(String.format("eixos na origem, %.0f unidades cada%s", len,
                cloud != null && !cloud.unit().isBlank() ? " | " + cloud.unit().split(";")[0] : ""), 8, viewH - 8);
    }

    /** Arredonda para 1, 2 ou 5 x 10^n. */
    private static double niceLength(double v) {
        if (v <= 0) return 1;
        double base = Math.pow(10, Math.floor(Math.log10(v)));
        double m = v / base;
        return base * (m < 1.5 ? 1 : m < 3.5 ? 2 : m < 7.5 ? 5 : 10);
    }

    private void updateInfo() {
        if (cloud == null) {
            info.setText("Nenhuma nuvem carregada.");
            return;
        }
        int n4 = 0, n3 = 0;
        for (int i : visible) {
            if (cloud.markers()[i] >= 4) n4++;
            else n3++;
        }
        info.setText(String.format("%,d pontos no arquivo | %,d exibidos (%,d de pose com 4 marcadores, %,d com 3) | "
                + "arrastar: girar | Shift/botão direito: mover | roda: zoom | duplo clique: reiniciar",
                cloud.size(), visible.length, n4, n3));
    }
}
