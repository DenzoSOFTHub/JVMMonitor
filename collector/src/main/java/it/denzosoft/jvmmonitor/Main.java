package it.denzosoft.jvmmonitor;

import it.denzosoft.jvmmonitor.cli.CliMain;
import it.denzosoft.jvmmonitor.gui.MainFrame;

/**
 * Unified entry point.
 *
 * Usage:
 *   java -jar jvmmonitor.jar gui                  Launch Swing GUI
 *   java -jar jvmmonitor.jar cli [args...]         Launch CLI
 *   java -jar jvmmonitor.jar connect <host> <port> Launch CLI + connect
 *   java -jar jvmmonitor.jar attach <pid>          Launch CLI + attach
 *   java -jar jvmmonitor.jar list                  List JVMs and exit
 *   java -jar jvmmonitor.jar                       Launch GUI (default)
 */
public class Main {

    public static void main(String[] args) {
        if (args.length == 0) {
            /* Default: launch GUI */
            MainFrame.launch();
            return;
        }

        String cmd = args[0].toLowerCase();

        if ("gui".equals(cmd)) {
            MainFrame.launch();
        } else if ("cli".equals(cmd)) {
            /* Strip "cli" from args and pass rest to CliMain */
            String[] cliArgs = new String[args.length - 1];
            System.arraycopy(args, 1, cliArgs, 0, cliArgs.length);
            CliMain.main(cliArgs);
        } else {
            /* Everything else goes to CLI (connect, attach, list, --help) */
            CliMain.main(args);
        }
    }
}
