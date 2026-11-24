package de.df.jauswertung.gui.plugins.zeitnahme;

import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;

import de.df.jauswertung.gui.UpdateEventConstants;
import de.df.jauswertung.gui.plugins.CorePlugin;
import de.df.jauswertung.gui.util.I18n;
import de.df.jutils.plugin.ANullPlugin;
import de.df.jutils.plugin.IPluginManager;
import de.df.jutils.plugin.MenuInfo;
import de.df.jutils.plugin.UpdateEvent;

public final class MZeitnahmePlugin extends ANullPlugin {
    private CorePlugin core;
    private JZeitnahmeLauf dialog;

    @Override
    public void setController(IPluginManager controller, String uid) {
        super.setController(controller, uid);
        core = (CorePlugin) controller.getFeature("de.df.jauswertung.core", uid);
    }

    @Override
    public MenuInfo[] getMenues() {
        JMenuItem eintrag = new JMenuItem(JZeitnahmeLauf.text("Titel"));
        eintrag.addActionListener(event -> {
            if (dialog == null || !dialog.isDisplayable()) {
                var quelle = new ZeitnahmeLaufquelle(core::getWettkampf);
                var zugriff = core.getWettkampfZugriff();
                var uebernahme = new ZeitnahmeErgebnisUebernahme(zugriff::versuchen, quelle::lauf,
                        () -> getController().sendDataUpdateEvent("ZeitnahmeErgebnisUebernahme",
                                UpdateEventConstants.REASON_POINTS_CHANGED, this));
                dialog = new JZeitnahmeLauf(getController().getWindow(),
                        new ZeitnahmeSnapshot(zugriff::versuchen, quelle::laeufe), uebernahme);
                dialog.setVisible(true);
            } else {
                dialog.toFront();
            }
        });
        return new MenuInfo[] { new MenuInfo(I18n.get("Information"), 550, new JMenuItem[] { eintrag }, 1051) };
    }

    @Override
    public void dataUpdated(UpdateEvent event) {
        SwingUtilities.invokeLater(() -> {
            if (dialog != null && dialog.isDisplayable()) {
                dialog.wettkampfGeaendert();
            }
        });
    }

    @Override
    public void shutDown() {
        SwingUtilities.invokeLater(() -> {
            if (dialog != null) {
                dialog.dispose();
            }
        });
    }
}
