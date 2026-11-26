package pl.lighty.launcher;

import com.google.gson.Gson;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class LauncherWindow extends JFrame implements Core.Listener {

    static final Color ACCENT = new Color(0xF5C518);

    static class Config {
        String username = "Player";
        String loader = "Vanilla";
        String version = "";
        int ram = 4096;
        String java = "";

        static Config load(Path root) {
            try {
                Path p = root.resolve("config.json");
                if (Files.exists(p)) return new Gson().fromJson(Files.readString(p), Config.class);
            } catch (Exception ignored) {}
            return new Config();
        }

        void save(Path root) {
            try {
                Files.createDirectories(root);
                Files.writeString(root.resolve("config.json"), new Gson().toJson(this));
            } catch (Exception ignored) {}
        }
    }

    final Core core = new Core(this);
    final Config cfg = Config.load(core.root);

    final JTextField nick = new JTextField(cfg.username, 16);
    final JComboBox<String> loader = new JComboBox<>(new String[]{"Vanilla", "Fabric", "Forge", "Quilt"});
    final JComboBox<String> version = new JComboBox<>();
    final JCheckBox snaps = new JCheckBox("Snapshoty");
    final JSpinner ram = new JSpinner(new SpinnerNumberModel(cfg.ram, 1024, 32768, 512));
    final JTextField java = new JTextField(cfg.java);
    final JButton play = new JButton("GRAJ");
    final JButton folder = new JButton("Folder gry");
    final JProgressBar bar = new JProgressBar(0, 1000);
    final JTextArea log = new JTextArea(10, 50);

    public LauncherWindow() {
        super("Lighty Launcher");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        loader.setSelectedItem(cfg.loader);
        build();
        loadVersions();
        pack();
        setMinimumSize(getSize());
        setLocationRelativeTo(null);
    }

    void build() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        c.fill = GridBagConstraints.HORIZONTAL;

        JLabel title = new JLabel("Lighty Launcher");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));
        title.setForeground(ACCENT);
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2;
        p.add(title, c);
        c.gridwidth = 1;

        java.setToolTipText("Puste = Java, na której działa launcher");
        row(p, c, 1, "Nick", nick);
        row(p, c, 2, "Loader", loader);
        row(p, c, 3, "Wersja", version);
        c.gridx = 1; c.gridy = 4; p.add(snaps, c);
        row(p, c, 5, "RAM (MB)", ram);
        row(p, c, 6, "Ścieżka Javy", java);

        play.setFont(play.getFont().deriveFont(Font.BOLD, 18f));
        play.setBackground(ACCENT);
        play.setForeground(Color.BLACK);
        play.setPreferredSize(new Dimension(100, 46));
        c.gridx = 0; c.gridy = 7; c.gridwidth = 2;
        p.add(play, c);
        c.gridy = 8; p.add(folder, c);
        c.gridy = 9; p.add(bar, c);

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        c.gridy = 10; c.weighty = 1; c.fill = GridBagConstraints.BOTH;
        p.add(new JScrollPane(log), c);

        setContentPane(p);

        snaps.addItemListener(e -> { if (e.getStateChange() != ItemEvent.DESELECTED || true) loadVersions(); });
        play.addActionListener(e -> startGame());
        folder.addActionListener(e -> openFolder());
    }

    static void row(JPanel p, GridBagConstraints c, int y, String label, JComponent comp) {
        c.gridx = 0; c.gridy = y; c.weightx = 0;
        p.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1;
        p.add(comp, c);
    }

    void loadVersions() {
        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() throws Exception {
                return core.listVersions(snaps.isSelected());
            }
            @Override protected void done() {
                try {
                    version.setModel(new DefaultComboBoxModel<>(get().toArray(new String[0])));
                    if (!cfg.version.isEmpty()) version.setSelectedItem(cfg.version);
                } catch (Exception ex) {
                    log("Nie udało się pobrać listy wersji: " + ex.getMessage());
                }
            }
        }.execute();
    }

    void openFolder() {
        try {
            String mc = (String) version.getSelectedItem();
            if (mc == null) return;
            Path dir = core.instanceDir(mc, (String) loader.getSelectedItem());
            Files.createDirectories(dir.resolve("mods"));
            Desktop.getDesktop().open(dir.toFile());
        } catch (Exception ex) {
            log("Nie można otworzyć folderu: " + ex.getMessage());
        }
    }

    void setBusy(boolean busy) {
        for (JComponent comp : new JComponent[]{play, nick, loader, version, snaps, ram, java})
            comp.setEnabled(!busy);
    }

    void startGame() {
        String name = nick.getText().trim();
        if (!name.matches("\\w{3,16}")) {
            JOptionPane.showMessageDialog(this, "Nick: 3-16 znaków (litery, cyfry, _).");
            return;
        }
        if (version.getSelectedItem() == null) return;

        cfg.username = name;
        cfg.loader = (String) loader.getSelectedItem();
        cfg.version = (String) version.getSelectedItem();
        cfg.ram = (Integer) ram.getValue();
        cfg.java = java.getText().trim();
        cfg.save(core.root);

        Core.Options opts = new Core.Options(name, cfg.version, cfg.loader, cfg.ram, cfg.java);
        setBusy(true);
        new Thread(() -> {
            try {
                Process proc = core.launch(opts);
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) log(line);
                }
                log("Gra zakończona (kod " + proc.waitFor() + ").");
            } catch (Exception ex) {
                log("BŁĄD: " + ex.getMessage());
            } finally {
                SwingUtilities.invokeLater(() -> setBusy(false));
                progress(0);
            }
        }, "lighty-launch").start();
    }

    @Override public void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            log.append(msg + "\n");
            log.setCaretPosition(log.getDocument().getLength());
        });
    }

    @Override public void progress(double f) {
        SwingUtilities.invokeLater(() -> bar.setValue((int) (f * 1000)));
    }
}
