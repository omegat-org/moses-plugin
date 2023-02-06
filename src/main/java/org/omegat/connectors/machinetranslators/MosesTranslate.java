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

package org.omegat.connectors.machinetranslators;

import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;

import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.cache.spi.CachingProvider;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingWorker;

import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import org.apache.xmlrpc.client.XmlRpcClient;
import org.apache.xmlrpc.client.XmlRpcClientConfigImpl;

import org.omegat.core.Core;
import org.omegat.core.CoreEvents;
import org.omegat.core.events.IProjectEventListener;
import org.omegat.filters2.html2.HTMLUtils;
import org.omegat.gui.exttrans.IMachineTranslation;
import org.omegat.gui.exttrans.MTConfigDialog;
import org.omegat.tokenizer.ITokenizer;
import org.omegat.util.DeNormalize;
import org.omegat.util.Language;
import org.omegat.util.OStrings;
import org.omegat.util.PatternConsts;
import org.omegat.util.Preferences;

/**
 * Support for Moses Server
 *
 * @author Aaron Madlon-Kay
 */
public class MosesTranslate implements IMachineTranslation {

    protected static final String ALLOW_MOSES_TRANSLATE = "allow_moses_translate";

    protected static final String PROPERTY_MOSES_URL = "moses.server.url";

    private static final ResourceBundle bundle = ResourceBundle.getBundle("MosesBundle");

    /**
     * Machine translation implementation can use this cache for skip requests
     * twice. Cache will be cleared when project change.
     */
    private final Cache<String, String> cache;
    private boolean enabled;

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
            Method compareVersions =
                    clazz.getMethod("compareVersions", String.class, String.class, String.class, String.class);
            if ((int) compareVersions.invoke(clazz, OStrings.VERSION, OStrings.UPDATE, requiredVersion, requiredUpdate)
                    < 0) {
                Core.pluginLoadingError("Moses Plugin cannot be loaded because OmegaT Version "
                        + OStrings.VERSION + " is lower than required version " + requiredVersion);
                return;
            }
        } catch (ClassNotFoundException
                 | NoSuchMethodException
                 | IllegalAccessException
                 | InvocationTargetException e) {
            Core.pluginLoadingError(
                    "Moses Plugin cannot be loaded because this OmegaT version is not supported");
            return;
        }
        Core.registerMachineTranslationClass(MosesTranslate.class);
    }

    /**
     * Plugin unloader.
     */
    @SuppressWarnings("unused")
    public static void unloadPlugins() {
    }

    public MosesTranslate() {
        if (Core.getMainWindow() != null) {
            JCheckBoxMenuItem menuItem = new JCheckBoxMenuItem();
            menuItem.setText(getName());
            menuItem.addActionListener(e -> setEnabled(menuItem.isSelected()));
            enabled = Preferences.isPreference(ALLOW_MOSES_TRANSLATE);
            menuItem.setState(enabled);
            Core.getMainWindow().getMainMenu().getMachineTranslationMenu().add(menuItem);
            // Preferences listener
            Preferences.addPropertyChangeListener(ALLOW_MOSES_TRANSLATE, e -> {
                boolean newValue = (Boolean) e.getNewValue();
                menuItem.setSelected(newValue);
                enabled = newValue;
            });
        }

        cache = getCacheLayer(getName());
        setCacheClearPolicy();
    }

    /**
     * Creat cache object.
     * <p>
     * MT connectors can override cache size and invalidate policy.
     * @param name name of cache which should be unique among MT connectors.
     * @return Cache object
     */
    protected Cache<String, String> getCacheLayer(String name) {
        CachingProvider provider = Caching.getCachingProvider();
        CacheManager manager = provider.getCacheManager();
        Cache<String, String> cache1 = manager.getCache(name);
        if (cache1 != null) {
            return cache1;
        }
        CaffeineConfiguration<String, String> config = new CaffeineConfiguration<>();
        config.setExpiryPolicyFactory(() -> new CreatedExpiryPolicy(Duration.ONE_DAY));
        config.setMaximumSize(OptionalLong.of(1_000));
        return manager.createCache(name, config);
    }

    /**
     * Register cache clear policy.
     */
    protected void setCacheClearPolicy() {
        CoreEvents.registerProjectChangeListener(eventType -> {
            if (eventType.equals(IProjectEventListener.PROJECT_CHANGE_TYPE.CLOSE)) {
                cache.clear();
            }
        });
    }

    public String getName() {
        return bundle.getString("MT_ENGINE_MOSES");
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean b) {
        enabled = b;
    }

    @Override
    public String getTranslation(Language sLang, Language tLang, String text) throws Exception {
        if (enabled) {
            return translate(sLang, tLang, text);
        } else {
            return null;
        }
    }

    @Override
    public String getCachedTranslation(Language sLang, Language tLang, String text) {
        if (enabled) {
            return getFromCache(sLang, tLang, text);
        } else {
            return null;
        }
    }

    protected String getFromCache(Language sLang, Language tLang, String text) {
        return cache.get(sLang + "/" + tLang + "/" + text);
    }

    protected String translate(Language sLang, Language tLang, String text) throws Exception {
        String server = getServerUrl();
        if (server == null) {
            throw new Exception(bundle.getString("MT_ENGINE_MOSES_URL_NOTFOUND"));
        }

        XmlRpcClient client = getClient(new URL(server));

        Map<String, String> mosesParams = new HashMap<>();
        mosesParams.put("text", mosesPreprocess(text, sLang.getLocale()));

        Object[] xmlRpcParams = { mosesParams };
        HashMap<?, ?> response = (HashMap<?, ?>) client.execute("translate", xmlRpcParams);
        return mosesPostprocess((String) response.get("text"), tLang);
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
        JButton testButton = new JButton(bundle.getString("MT_ENGINE_MOSES_TEST_BUTTON"));
        testButton.addActionListener(e -> {
            messageLabel.setText(bundle.getString("MT_ENGINE_MOSES_TEST_TESTING"));
            String url = dialog.panel.valueField1.getText().trim();
            new SwingWorker<String, Void>() {
                @Override
                protected String doInBackground() throws Exception {
                    XmlRpcClient client = getClient(new URL(url));
                    Object response = client.execute("system.listMethods", (Object[]) null);
                    if (Arrays.asList(((Object[]) response)).contains("translate")) {
                        return bundle.getString("MT_ENGINE_MOSES_TEST_RESULT_OK");
                    } else {
                        return bundle.getString("MT_ENGINE_MOSES_TEST_RESULT_NO_TRANSLATE");
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

        dialog.panel.valueLabel1.setText(bundle.getString("MT_ENGINE_MOSES_URL_LABEL"));
        dialog.panel.valueField1.setText(getServerUrl());
        dialog.panel.valueField1.setColumns(20);

        dialog.panel.valueLabel2.setVisible(false);
        dialog.panel.valueField2.setVisible(false);

        dialog.panel.temporaryCheckBox.setVisible(false);

        dialog.show();
    }

    /**
     * Attempt to clean spaces added around tags by machine translators. Do it
     * by comparing spaces between the source text and the machine translated
     * text.
     *
     * @param machineText
     *            The text returned by the machine translator
     * @param sourceText
     *            The original source segment
     * @return replaced text
     */
    protected String cleanSpacesAroundTags(String machineText, String sourceText) {

        // Spaces after
        Matcher tag = PatternConsts.OMEGAT_TAG_SPACE.matcher(machineText);
        while (tag.find()) {
            String searchTag = tag.group();
            if (!sourceText.contains(searchTag)) { // The tag didn't appear
                // with a trailing space
                // in the source text
                String replacement = searchTag.substring(0, searchTag.length() - 1);
                machineText = machineText.replace(searchTag, replacement);
            }
        }

        // Spaces before
        tag = PatternConsts.SPACE_OMEGAT_TAG.matcher(machineText);
        while (tag.find()) {
            String searchTag = tag.group();
            if (!sourceText.contains(searchTag)) { // The tag didn't appear
                // with a leading space
                // in the source text
                String replacement = searchTag.substring(1);
                machineText = machineText.replace(searchTag, replacement);
            }
        }
        return machineText;
    }
}
