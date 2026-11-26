package pl.lighty.launcher;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.SwingUtilities;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            FlatDarkLaf.setup();
            new LauncherWindow().setVisible(true);
        });
    }
}
