package de.df.jauswertung.gui.plugins.zeitnahme;

import javax.swing.JMenuItem;

import de.df.jauswertung.gui.util.I18n;
import de.df.jutils.plugin.ANullPlugin;
import de.df.jutils.plugin.MenuInfo;

public final class MZeitnahmeSimulatorPlugin extends ANullPlugin {
    @Override
    public MenuInfo[] getMenues() {
        JMenuItem eintrag = new JMenuItem(I18n.get("ZeitnahmeSimulator.Titel"));
        eintrag.addActionListener(event -> new JZeitnahmeSimulator(getController().getWindow()).setVisible(true));
        return new MenuInfo[] { new MenuInfo(I18n.get("Information"), 550, new JMenuItem[] { eintrag }, 1050) };
    }
}
