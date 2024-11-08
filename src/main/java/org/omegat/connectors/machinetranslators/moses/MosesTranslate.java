/**************************************************************************
 OmegaT - Computer Assisted Translation (CAT) tool
          with fuzzy matching, translation memory, keyword search,
          glossaries, and translation leveraging into updated projects.

 Copyright (C) 2014, 2017 Aaron Madlon-Kay
               2023 Hiroshi Miura
               Home page: http://www.omegat.org/
               Support center: https://omegat.org/support

 This file is part of OmegaT. The real license is reproduced below.

 OmegaT is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 OmegaT is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 **************************************************************************/

package org.omegat.connectors.machinetranslators.moses;

import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingWorker;

import org.apache.xmlrpc.client.XmlRpcClient;
import org.apache.xmlrpc.client.XmlRpcClientConfigImpl;

import org.omegat.core.Core;
import org.omegat.util.HTMLUtils;
import org.omegat.gui.exttrans.IMachineTranslation;
import org.omegat.gui.exttrans.MTConfigDialog;
import org.omegat.tokenizer.ITokenizer;
import org.omegat.util.Language;
import org.omegat.util.OStrings;
import org.omegat.util.Preferences;

/**
 * Support for Moses Server.
 *
 * @author Aaron Madlon-Kay
 */
public final class MosesTranslate extends BaseTranslate implements IMachineTranslation {

    static final String ALLOW_MOSES_TRANSLATE = "allow_moses_translate";

    static final String PROPERTY_MOSES_URL = "moses.server.url";

    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("MosesBundle");

    /**
     * Plugin loader.
     */
    @SuppressWarnings("unused")
    public static void loadPlugins() {
        // detect OmegaT version. Moses MT connector is dropped from 5.8.0
        String requiredVersion = "5.8.0";
        String requiredUpdate = "0";
        try {
            Class<?> clazz = Class.forName("org.omegat.util.VersionChecker");
            Method compareVersions = clazz.getMethod("compareVersions", String.class, String.class,
                    String.class, String.class);
            if ((int) compareVersions.invoke(clazz, OStrings.VERSION, OStrings.UPDATE, requiredVersion,
                    requiredUpdate) < 0) {
                Core.pluginLoadingError("Moses Plugin cannot be loaded because OmegaT Version "
                        + OStrings.VERSION + " is lower than required version " + requiredVersion);
                return;
            }
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                | InvocationTargetException e) {
            Core.pluginLoadingError(
                    "Moses Plugin cannot be loaded because this OmegaT version is not supported");
            return;
        }
        Core.registerMachineTranslationClass(MosesTranslate.class);
    }

    /**
     * Plugin un-loader.
     */
    @SuppressWarnings("unused")
    public static void unloadPlugins() {
    }

    /**
     * Constructor.
     */
    public MosesTranslate() {
        super();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return BUNDLE.getString("MT_ENGINE_MOSES");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getPreferenceName() {
        return ALLOW_MOSES_TRANSLATE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String translate(Language sLang, Language tLang, String text) throws Exception {
        String server = getServerUrl();
        if (server == null) {
            throw new Exception(BUNDLE.getString("MT_ENGINE_MOSES_URL_NOTFOUND"));
        }

        XmlRpcClient client = getClient(new URL(server));

        Map<String, String> mosesParams = new HashMap<>();
        mosesParams.put("text", mosesPreprocess(text, sLang.getLocale()));

        Object[] xmlRpcParams = {mosesParams};
        HashMap<?, ?> response = (HashMap<?, ?>) client.execute("translate", xmlRpcParams);
        String result = mosesPostprocess((String) response.get("text"), tLang);
        putToCache(sLang, tLang, text, result);
        return result;
    }

    private XmlRpcClient getClient(URL url) {
        XmlRpcClient client = new XmlRpcClient();
        XmlRpcClientConfigImpl config = new XmlRpcClientConfigImpl();
        config.setServerURL(url);
        client.setConfig(config);
        return client;
    }

    private String getServerUrl() {
        return System.getProperty(PROPERTY_MOSES_URL, Preferences.getPreference(PROPERTY_MOSES_URL));
    }

    private String mosesPreprocess(String text, Locale locale) {
        ITokenizer tokenizer = Core.getProject().getSourceTokenizer();
        StringBuilder sb = new StringBuilder();
        for (String t : tokenizer.tokenizeVerbatimToStrings(text)) {
            sb.append(t);
            sb.append(" ");
        }
        String result = sb.toString();
        return result.toLowerCase(locale);
    }

    private String mosesPostprocess(String text, Language targetLanguage) {
        String result = HTMLUtils.entitiesToChars(text);

        result = DeNormalize.processSingleLine(result).replaceAll("\\s+", " ").trim();

        if (!targetLanguage.isSpaceDelimited()) {
            result = result.replaceAll("(?<=[\u3001-\u9fa0])\\s+(?=[\u3001-\u9fa0])", "");
        }

        return cleanSpacesAroundTags(result, text);
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public void showConfigurationUI(Window parent) {
        MTConfigDialog dialog = new MTConfigDialog(parent, getName()) {
            @Override
            protected void onConfirm() {
                String url = panel.valueField1.getText().trim();
                System.setProperty(PROPERTY_MOSES_URL, url);
                Preferences.setPreference(PROPERTY_MOSES_URL, url);
            }
        };

        JLabel messageLabel = new JLabel();
        JButton testButton = new JButton(BUNDLE.getString("MT_ENGINE_MOSES_TEST_BUTTON"));
        testButton.addActionListener(e -> {
            messageLabel.setText(BUNDLE.getString("MT_ENGINE_MOSES_TEST_TESTING"));
            String url = dialog.panel.valueField1.getText().trim();
            new SwingWorker<String, Void>() {
                @Override
                protected String doInBackground() throws Exception {
                    XmlRpcClient client = getClient(new URL(url));
                    Object response = client.execute("system.listMethods", (Object[]) null);
                    if (Arrays.asList(((Object[]) response)).contains("translate")) {
                        return BUNDLE.getString("MT_ENGINE_MOSES_TEST_RESULT_OK");
                    } else {
                        return BUNDLE.getString("MT_ENGINE_MOSES_TEST_RESULT_NO_TRANSLATE");
                    }
                }

                @Override
                protected void done() {
                    String message;
                    try {
                        message = get();
                    } catch (ExecutionException e) {
                        message = e.getCause().getLocalizedMessage();
                        Logger.getLogger(getClass().getName()).log(Level.SEVERE, message, e);
                    } catch (Exception e) {
                        message = e.getLocalizedMessage();
                        Logger.getLogger(getClass().getName()).log(Level.SEVERE, message, e);
                    }
                    messageLabel.setText(message);
                }
            }.execute();

        });
        JPanel testPanel = new JPanel();
        testPanel.setLayout(new BoxLayout(testPanel, BoxLayout.LINE_AXIS));
        testPanel.add(testButton);
        testPanel.add(messageLabel);
        testPanel.setAlignmentX(0);
        dialog.panel.itemsPanel.add(testPanel);

        dialog.panel.valueLabel1.setText(BUNDLE.getString("MT_ENGINE_MOSES_URL_LABEL"));
        dialog.panel.valueField1.setText(getServerUrl());
        dialog.panel.valueField1.setColumns(20);

        dialog.panel.valueLabel2.setVisible(false);
        dialog.panel.valueField2.setVisible(false);

        dialog.panel.temporaryCheckBox.setVisible(false);

        dialog.show();
    }
}
