package xyz.shadowmere.desktop;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Drives the user actions with no feeds, network changes, or live proxy processes. */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="moondrop.test.ui",matches="true")
class AppWindowUsabilityTest {
    private static final Server SERVER=new Server("fixture","localhost",443,"aes-256-gcm","test","CA","Toronto","",true);

    @Test void primaryActionConnectsCancelsAndDisconnectsWithSystemProxyDefault() throws Exception {
        withWindow((window,actions) -> {
            window.setSystemProxySupported(true);window.setServers(List.of(SERVER));
            JButton primary=field(window,"quickConnect",JButton.class);
            assertEquals("Connect",primary.getText());primary.doClick();
            assertEquals(1,actions.quickConnections);assertTrue(actions.useSystem);
            window.setBusy(true);assertEquals("Cancel",primary.getText());primary.doClick();
            assertEquals(1,actions.disconnects);
            window.setBusy(false);window.setConnection("Connected","System proxy",true);
            assertEquals("Disconnect",primary.getText());assertTrue(primary.isEnabled());primary.doClick();
            assertEquals(2,actions.disconnects);
            assertFalse(field(window,"connect",JButton.class).isVisible(),"Do not show two Disconnect buttons");
            JComboBox<?> sort=field(window,"sort",JComboBox.class);
            assertEquals("Sort: recommended",sort.getItemAt(0));
        });
    }

    @Test void advancedModeRevealsControlsAndSavingSimpleModePreservesProtocolChoices() throws Exception {
        withWindow((window,actions) -> {
            window.setSystemProxySupported(true);window.setServers(List.of(SERVER));
            assertFalse(field(window,"enginePanel",JPanel.class).isVisible());
            assertFalse(field(window,"proxyControls",JPanel.class).isVisible());
            saveAdvancedMode(window,true);
            assertTrue(actions.settings.advancedMode());
            assertTrue(field(window,"enginePanel",JPanel.class).isVisible());
            assertTrue(field(window,"proxyControls",JPanel.class).isVisible());
            actions.settings=new AppSettings(true,true,true,true,Set.of("shadowsocks"),true);
            saveAdvancedMode(window,false);
            assertFalse(actions.settings.advancedMode());
            assertEquals(Set.of("shadowsocks"),actions.settings.enabledProtocols());
            assertFalse(field(window,"enginePanel",JPanel.class).isVisible());
        });
    }

    @Test void firstRunShowsTestingAndWorkingResultsWhileConnectRemainsAvailable() throws Exception {
        withWindow((window,actions) -> {
            window.setServers(List.of(SERVER));window.setSpeedTesting(true,"0 cached · 1 checks remaining…");
            JLabel summary=field(window,"discovery",JLabel.class);
            assertTrue(summary.getText().contains("Finding your fastest connection"));
            assertTrue(field(window,"quickConnect",JButton.class).isEnabled());
            window.setPerformance(SERVER,ServerPerformance.measured(List.of(30L,31L,32L),3));
            // Completing a progress event also refreshes the summary, as it does in a scan.
            window.setSpeedTesting(true,"1 checked");
            assertTrue(summary.getText().contains("1 working"));
            assertTrue(summary.getText().contains("testing continues"));
            window.setSpeedTesting(false,"Results saved");
            assertTrue(summary.getText().contains("saved tests reused"));
            window.setSystemProxySupported(false);
            assertTrue(field(window,"proxyControls",JPanel.class).isVisible());
            assertFalse(field(window,"systemProxy",JCheckBox.class).isSelected());
        });
    }

    @Test void detailsCanBeExpandedWithoutEnablingAdvancedMode() throws Exception {
        withWindow((window,actions) -> {
            window.setServers(List.of(SERVER));
            JLabel explanation=field(window,"selectedExplanation",JLabel.class);
            assertFalse(explanation.isVisible());
            JToggleButton details=field(window,"serverDetails",JToggleButton.class);
            details.doClick();assertTrue(explanation.isVisible());
            assertTrue(explanation.getText().contains("Shadowsocks encrypts"));
            assertFalse(actions.settings.advancedMode());
        });
    }

    @Test void errorRecoveryRetestsEvenWhenBackgroundTestingWasRunning() throws Exception {
        AtomicReference<FakeActions> recorded=new AtomicReference<>();
        withWindow((window,actions) -> {
            recorded.set(actions);window.setServers(List.of(SERVER));window.setSpeedTesting(true,"Testing…");
            Timer timer=new Timer(100,event -> {
                for(Window open:Window.getWindows())if(open instanceof JDialog dialog&&dialog.isVisible()) {
                    AbstractButton button=findButton(dialog,"Retest servers");if(button!=null)button.doClick();
                }
            });timer.setRepeats(false);timer.start();
            window.showConnectionError("Finding a server","The connection failed.");
        });
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1,recorded.get().forcedRetests);
        assertEquals(0,recorded.get().retestToggles);
    }

    @Test void simpleResultsShowLatencyAndAgeWithoutDetailedMetrics() {
        assertEquals("Not tested",AppWindow.simpleResult(ServerPerformance.untested()));
        ServerPerformance measured=new ServerPerformance(ServerPerformance.State.VERIFIED,42,Instant.now().minusSeconds(7200),3,3,1000000,Instant.now().minusSeconds(7200));
        String text=AppWindow.simpleResult(measured);
        assertTrue(text.contains("42 ms"));assertTrue(text.contains("2h ago"));
        assertFalse(text.contains("Mb/s"));assertFalse(text.contains("100%"));
    }

    private static void saveAdvancedMode(AppWindow window,boolean enabled) {
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Timer timer=new Timer(100,event -> {
            try {
                JDialog dialog=java.util.Arrays.stream(Window.getWindows()).filter(w -> w instanceof JDialog&&w.isVisible()).map(w -> (JDialog)w).findFirst().orElseThrow();
                JCheckBox toggle=(JCheckBox)findButton(dialog,"Advanced mode");
                if(toggle.isSelected()!=enabled)toggle.doClick();
                findButton(dialog,"Save settings").doClick();
            } catch(Throwable error) {
                failure.set(error);for(Window open:Window.getWindows())if(open instanceof JDialog)open.dispose();
            }
        });timer.setRepeats(false);timer.start();
        field(window,"settingsButton",JButton.class).doClick();
        if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    private static AbstractButton findButton(Container parent,String text) {
        for(Component child:parent.getComponents()) {
            if(child instanceof AbstractButton button&&text.equals(button.getText()))return button;
            if(child instanceof Container container) {AbstractButton result=findButton(container,text);if(result!=null)return result;}
        }
        return null;
    }
    private static <T>T field(AppWindow window,String name,Class<T> type) {
        try {Field field=AppWindow.class.getDeclaredField(name);field.setAccessible(true);return type.cast(field.get(window));}
        catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    private interface Scenario {void run(AppWindow window,FakeActions actions) throws Exception;}
    private static void withWindow(Scenario scenario) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(),"Run GUI checks under xvfb-run with -Djava.awt.headless=false");
        SwingUtilities.invokeAndWait(() -> {
            FakeActions actions=new FakeActions();AppWindow window=new AppWindow(actions);
            try {scenario.run(window,actions);}catch(Exception error){throw new AssertionError(error);}
            finally {field(window,"performanceRefresh",Timer.class).stop();window.dispose();}
        });
    }
    private static class FakeActions implements AppWindow.Actions {
        AppSettings settings=AppSettings.defaults();int quickConnections,disconnects,forcedRetests,retestToggles;boolean useSystem;
        public AppSettings settings(){return settings;}
        public boolean saveSettings(AppSettings value){settings=value;return true;}
        public void quickConnect(boolean useSystem){quickConnections++;this.useSystem=useSystem;}
        public void disconnect(){disconnects++;}
        public void refresh(){}public void retestSpeeds(){retestToggles++;}public void forceRetestSpeeds(){forcedRetests++;}public void checkForUpdates(){}public void restartToUpdate(){}
        public void connect(Server server,boolean useSystem){}public void installCore(){}
    }
}
