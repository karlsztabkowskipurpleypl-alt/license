# Lighty Launcher

Własny launcher Minecraft Java: Vanilla, Fabric, Forge, Quilt.

## Budowanie

Wymagane: JDK 17+ i Maven.

    mvn package
    java -jar target/LightyLauncher.jar

## Gdzie co jest

Dane: `%APPDATA%\.lightylauncher` (Windows), `~/.lightylauncher` (Linux), `~/Library/Application Support/lightylauncher` (macOS).
Każda kombinacja wersja + loader ma osobny folder: `instances/<wersja>-<loader>/` (mody wrzucasz do `mods/`).

## Pliki

- `Main.java` – start programu
- `LauncherWindow.java` – interfejs (Swing + FlatLaf)
- `Core.java` – pobieranie, instalacja loaderów, budowanie komendy startowej

## Ograniczenia / do zrobienia

- Logowanie tylko offline. Do serwerów premium trzeba dodać logowanie Microsoft (wymaga własnego Client ID z Azure).
- Forge: wspierane wersje 1.13+ (nowszy format instalatora).
- Java: używana jest ta, na której działa launcher, albo ścieżka z pola "Ścieżka Javy". Auto-pobieranie Javy do dodania.
