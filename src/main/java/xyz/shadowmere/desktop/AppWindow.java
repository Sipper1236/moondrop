package xyz.shadowmere.desktop;

import javax.swing.*;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Comparator;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;

/** The desktop view. Networking and process lifecycle live in the controller. */
public final class AppWindow extends JFrame {
    public interface Actions {
        AppSettings settings();
        boolean saveSettings(AppSettings settings);
        void refresh();
        void retestSpeeds();
        default void forceRetestSpeeds() { retestSpeeds(); }
        void checkForUpdates();
        void restartToUpdate();
        void quickConnect(boolean systemProxy);
        void connect(Server server, boolean systemProxy);
        void disconnect();
        void installCore();
        default void copyDiagnostics() { }
    }
    private static final Color BG = new Color(18,25,33), PANEL = new Color(26,35,45),
            TEXT = new Color(237,240,231), MUTED = new Color(148,164,174), MINT = new Color(157,229,193);
    private final Actions actions;
    private final ServerListModel model = new ServerListModel();
    private static final ServerPerformance UNTESTED = ServerPerformance.untested();
    private final Map<Server,String> searchText = new HashMap<>();
    private final Map<Server,String> countryNames = new HashMap<>();
    private final Map<String,String> countryCodes = new HashMap<>();
    private boolean updatingFilters;
    private final JList<Server> servers = new JList<>(model) {
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (getModel().getSize() == 0) {
                graphics.setColor(MUTED); graphics.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,13));
                String message = actions.settings().enabledProtocols().isEmpty()?"Enable a protocol in Settings → Advanced mode":all.isEmpty() ? "Refresh to discover available servers" : "No servers match your search";
                graphics.drawString(message, Math.max(12, (getWidth()-graphics.getFontMetrics().stringWidth(message))/2), 48);
            }
        }
    };
    private final JTextField search = new JTextField();
    private final JComboBox<String> country = new JComboBox<>(new String[]{"All locations"});
    private final Map<Server, ServerPerformance> performance = new HashMap<>();
    private final JComboBox<String> sort = new JComboBox<>(new String[]{"Sort: recommended", "Sort: country"});
    private final JButton retest = new JButton("Retest speeds");
    private final JButton settingsButton = iconButton("settings", "Settings");
    private final JButton update = iconButton("update", "Check for updates");
    private final JLabel updateStatus = label("", MUTED, 11);
    private boolean updateBusy;
    private String updateVersion;
    private final JLabel speedProgress = label("Latency · 7-day cache", MUTED, 11);
    private final JLabel discovery = label("Loading servers…", MUTED, 12);
    private final JLabel automaticHint = label("Automatically chooses the best tested server", MUTED, 11);
    private final Timer performanceRefresh = new Timer(150, e -> filter());
    private boolean speedTesting, rebuilding;
    private boolean catalogRefreshing, savedCatalog;
    private final JLabel count = label("Refresh to discover servers", MUTED, 12);
    private final JLabel status = label("Disconnected", TEXT, 28);
    private final JLabel detail = label("Your connection is unchanged.", MUTED, 13);
    private final JLabel selectedName = label("Choose a server", TEXT, 19);
    private final JLabel selectedDetail = label("Select a location from the list.", MUTED, 12);
    private final JLabel selectedProtocol = new ProtocolBadge();
    private final JLabel selectedStrength = label("",MINT,11);
    private final JLabel selectedExplanation = label("",MUTED,11);
    private final JPanel selectedProtection = vertical();
    private final JButton quickConnect = new JButton("Connect");
    private final JButton connect = new JButton("Connect selected server"), refresh = iconButton("refresh", "Refresh servers"), install = new JButton("Install engine");
    private final JCheckBox systemProxy = new JCheckBox("Use as system proxy");
    private final JTextArea log = new JTextArea();
    private final JPanel proxyControls = vertical();
    private final JPanel enginePanel = panel(new BorderLayout(0,10));
    private final JToggleButton serverDetails = new JToggleButton("Show server details");
    private List<Server> all = List.of();
    private boolean connected, busy, coreReady, systemProxySupported = true;

    public AppWindow(Actions actions) {
        super("Moondrop");
        this.actions = actions;
        performanceRefresh.setRepeats(false);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(900,780));
        setSize(1060,820);
        setLocationRelativeTo(null);
        JPanel root = new JPanel(new BorderLayout(28,24));
        root.setBackground(BG); root.setBorder(new EmptyBorder(30,34,26,34)); setContentPane(root);
        JPanel header = new JPanel(new BorderLayout()); header.setOpaque(false);
        JPanel brand = new JPanel(new FlowLayout(FlowLayout.LEFT,12,0)); brand.setOpaque(false);
        brand.add(new Mark());
        JPanel words = vertical(); words.add(label("MOONDROP", TEXT,20)); words.add(label("by rayyan", MUTED,12)); brand.add(words);
        header.add(brand,BorderLayout.WEST); refresh.addActionListener(e -> actions.refresh()); JPanel headerTools=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));headerTools.setOpaque(false);updateStatus.setPreferredSize(new Dimension(125,30));headerTools.add(updateStatus);update.setToolTipText("Check GitHub for a newer Moondrop release");update.addActionListener(e -> {if(updateVersion!=null)actions.restartToUpdate();else actions.checkForUpdates();});headerTools.add(update);headerTools.add(refresh); settingsButton.addActionListener(e -> showSettings()); headerTools.add(settingsButton);header.add(headerTools,BorderLayout.EAST); root.add(header,BorderLayout.NORTH);
        JPanel body = new JPanel(new GridLayout(1,2,26,0)); body.setOpaque(false); root.add(body,BorderLayout.CENTER);
        JPanel browser = panel(new BorderLayout(0,14)); body.add(browser);
        country.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus) {
                JLabel item=(JLabel)super.getListCellRendererComponent(list,value,index,selected,focus);
                item.setBorder(new EmptyBorder(6,8,6,8));item.setIconTextGap(9);
                item.setBackground(selected?new Color(44,66,65):new Color(32,44,55));item.setForeground(TEXT);
                String code=countryCodes.get(value);item.setIcon(code==null?null:FlagIcons.get(code));
                return item;
            }
        });
        sort.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus) {
                JLabel item=(JLabel)super.getListCellRendererComponent(list,value,index,selected,focus);
                item.setBorder(new EmptyBorder(6,8,6,8));item.setBackground(selected?new Color(44,66,65):new Color(32,44,55));item.setForeground(TEXT);return item;
            }
        });
        browser.add(serverFilters(search,country,sort),BorderLayout.NORTH);
        sort.addActionListener(e -> filter());
        servers.setBackground(PANEL); servers.setForeground(TEXT); servers.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); servers.setFixedCellHeight(84); servers.setFixedCellWidth(280); servers.setCellRenderer(new ServerRenderer());
        JScrollPane scroll = new JScrollPane(servers); scroll.setBorder(BorderFactory.createEmptyBorder()); scroll.getViewport().setBackground(PANEL); browser.add(scroll,BorderLayout.CENTER);
        JPanel listFooter = vertical(); JPanel counts = new JPanel(new BorderLayout(8,0)); counts.setOpaque(false); counts.add(count,BorderLayout.CENTER); retest.setToolTipText("Retest every server, including cached results. Use after changing networks or routes. Completed checks are saved even if testing stops."); retest.setMargin(new Insets(5,8,5,8)); retest.addActionListener(e -> actions.retestSpeeds()); counts.add(retest,BorderLayout.EAST); counts.setAlignmentX(Component.LEFT_ALIGNMENT); listFooter.add(counts); listFooter.add(Box.createVerticalStrut(6)); listFooter.add(speedProgress); speedProgress.setToolTipText("Three HTTPS checks measure reliability and median latency. The best five candidates receive a bounded 1 MiB download test. Completed results are reused for 7 days; scans resume missing checks."); browser.add(listFooter,BorderLayout.SOUTH);
        JPanel right = new JPanel(new BorderLayout(0,18)); right.setOpaque(false); body.add(right);
        JPanel connection = panel(new BorderLayout(0,10)); connection.setBorder(new EmptyBorder(16,20,16,20)); right.add(connection,BorderLayout.NORTH);
        JPanel state = vertical(); state.add(label("YOUR CONNECTION",MUTED,11)); state.add(Box.createVerticalStrut(10)); state.add(status); state.add(Box.createVerticalStrut(8)); state.add(detail); connection.add(state,BorderLayout.NORTH);
        JPanel chosen = vertical(); chosen.add(selectedName); chosen.add(Box.createVerticalStrut(5)); chosen.add(selectedDetail); chosen.add(Box.createVerticalStrut(10));
        JPanel selectedFeatures=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));selectedFeatures.setOpaque(false);selectedFeatures.setAlignmentX(Component.LEFT_ALIGNMENT);
        selectedFeatures.add(selectedProtocol);selectedFeatures.add(selectedStrength);
        selectedProtection.add(selectedFeatures);selectedProtection.add(Box.createVerticalStrut(6));selectedProtection.add(selectedExplanation);
        selectedProtection.setVisible(false); serverDetails.setAlignmentX(Component.LEFT_ALIGNMENT);serverDetails.addActionListener(e -> {selectedExplanation.setVisible(serverDetails.isSelected());serverDetails.setText(serverDetails.isSelected()?"Hide server details":"Show server details");});chosen.add(selectedProtection);chosen.add(serverDetails);chosen.add(Box.createVerticalStrut(14));
        systemProxy.setOpaque(false); systemProxy.setForeground(TEXT); systemProxy.setAlignmentX(Component.LEFT_ALIGNMENT); proxyControls.add(systemProxy); proxyControls.add(Box.createVerticalStrut(6)); proxyControls.add(label("Local SOCKS5 / HTTP: 127.0.0.1:17890",MUTED,11)); proxyControls.add(Box.createVerticalStrut(5)); proxyControls.add(label("System proxy applies to supported apps.",MUTED,11)); chosen.add(proxyControls); connection.add(chosen,BorderLayout.CENTER);
        connect.setPreferredSize(new Dimension(200,44)); connect.setBackground(new Color(43,58,67)); connect.setForeground(TEXT); connect.setFont(connect.getFont().deriveFont(Font.BOLD,14)); connect.addActionListener(e -> {if(connected || busy) actions.disconnect(); else if(servers.getSelectedValue()!=null) actions.connect(servers.getSelectedValue(),systemProxy.isSelected());}); JPanel connectionButtons = vertical();
        quickConnect.setBackground(MINT); quickConnect.setForeground(BG); quickConnect.setFont(quickConnect.getFont().deriveFont(Font.BOLD,21)); quickConnect.setAlignmentX(Component.LEFT_ALIGNMENT); quickConnect.setMaximumSize(new Dimension(Integer.MAX_VALUE,84)); quickConnect.setMinimumSize(new Dimension(250,84)); quickConnect.setPreferredSize(new Dimension(350,84)); quickConnect.putClientProperty("FlatLaf.style","arc: 20");quickConnect.setToolTipText("Automatically test servers and connect to the fastest verified result");quickConnect.addActionListener(e -> {if(connected||busy)actions.disconnect();else actions.quickConnect(systemProxy.isSelected());});
        connectionButtons.add(quickConnect); connectionButtons.add(Box.createVerticalStrut(9)); connectionButtons.add(automaticHint); connectionButtons.add(Box.createVerticalStrut(8)); connectionButtons.add(discovery); connectionButtons.add(Box.createVerticalStrut(10)); connect.setAlignmentX(Component.LEFT_ALIGNMENT); connect.setMaximumSize(new Dimension(Integer.MAX_VALUE,40)); connectionButtons.add(connect); connection.add(connectionButtons,BorderLayout.SOUTH);
        JPanel engine = enginePanel; engine.setBorder(new EmptyBorder(14,20,14,20)); right.add(engine,BorderLayout.CENTER);
        JPanel engineHeader = new JPanel(new BorderLayout()); engineHeader.setOpaque(false); engineHeader.add(label("ENGINE & ACTIVITY",MUTED,11),BorderLayout.WEST); install.addActionListener(e -> actions.installCore()); engineHeader.add(install,BorderLayout.EAST); engine.add(engineHeader,BorderLayout.NORTH);
        log.setEditable(false); log.setLineWrap(true); log.setWrapStyleWord(true); log.setBackground(PANEL); log.setForeground(MUTED); log.setFont(new Font(Font.MONOSPACED,Font.PLAIN,11)); JScrollPane logs = new JScrollPane(log); logs.setBorder(BorderFactory.createEmptyBorder()); logs.setPreferredSize(new Dimension(300,120)); engine.add(logs,BorderLayout.CENTER);
        search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){filter();}public void removeUpdate(DocumentEvent e){filter();}public void changedUpdate(DocumentEvent e){filter();}});
        applyMode(); country.addActionListener(e -> {if(!updatingFilters)filter();}); servers.addListSelectionListener(e -> {if(connected||rebuilding)return; showSelection(servers.getSelectedValue()); updateControls();}); updateControls();
    }
    private void showSettings() {
        AppSettings current=actions.settings();
        JCheckBox advanced=new JCheckBox("Advanced mode",current.advancedMode());
        JCheckBox updates=new JCheckBox("Check for updates automatically",current.automaticUpdates());
        JCheckBox testing=new JCheckBox("Test servers in the background",current.automaticTesting());
        JCheckBox nearby=new JCheckBox("Test nearby countries first",current.nearbyFirst());
        JCheckBox proxy=new JCheckBox("Use system proxy by default",current.systemProxy());
        proxy.setEnabled(systemProxySupported);
        JPanel content=vertical(); content.setBorder(new EmptyBorder(10,6,10,6));
        content.add(label("CONNECTION",MUTED,11));content.add(Box.createVerticalStrut(10));
        for(JCheckBox option:List.of(testing,proxy))addOption(content,option);
        content.add(label("Completed tests are saved for 7 days and rechecked when needed.",MUTED,11));
        content.add(Box.createVerticalStrut(14));content.add(label("UPDATES",MUTED,11));
        addOption(content,updates);
        JButton diagnostics=new JButton("Copy diagnostics");diagnostics.addActionListener(e -> actions.copyDiagnostics());
        diagnostics.setAlignmentX(Component.LEFT_ALIGNMENT);content.add(diagnostics);
        content.add(Box.createVerticalStrut(14));addOption(content,advanced);
        content.add(label("Show protocol controls, measurements and engine activity.",MUTED,11));
        JPanel advancedOptions=vertical();advancedOptions.add(Box.createVerticalStrut(12));
        addOption(advancedOptions,nearby);
        advancedOptions.add(label("PROTOCOLS",MUTED,11));advancedOptions.add(Box.createVerticalStrut(8));
        Map<String,JCheckBox> protocols=new java.util.LinkedHashMap<>();
        for(String protocol:AppSettings.PROTOCOLS) {
            String name=switch(protocol) {case "shadowsocks" -> "Shadowsocks";case "vless" -> "VLESS (TLS / REALITY)";case "trojan" -> "Trojan";case "hysteria2" -> "Hysteria 2";default -> "TUIC";};
            JCheckBox option=new JCheckBox(name,current.enabledProtocols().contains(protocol));
            protocols.put(protocol,option);addOption(advancedOptions,option);
        }
        advancedOptions.add(label("Disabled protocols are hidden and skipped.",MUTED,11));
        advancedOptions.add(label("Your current connection stays active until you disconnect.",MUTED,11));
        advancedOptions.setVisible(advanced.isSelected());content.add(advancedOptions);
        advanced.addActionListener(e -> {
            advancedOptions.setVisible(advanced.isSelected());
            Window dialog=SwingUtilities.getWindowAncestor(content);if(dialog!=null){dialog.pack();dialog.setLocationRelativeTo(this);}
        });
        content.add(Box.createVerticalStrut(10));
        content.add(label(systemProxySupported?"Proxy preference applies to your next connection.":"Linux: configure apps to use the local proxy at 127.0.0.1:17890.",MUTED,11));
        int choice=JOptionPane.showOptionDialog(this,content,"Settings",JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE,null,new String[]{"Save settings","Cancel"},"Save settings");
        if(choice==0&&actions.saveSettings(new AppSettings(updates.isSelected(),testing.isSelected(),nearby.isSelected(),proxy.isSelected(),protocols.entrySet().stream().filter(entry -> entry.getValue().isSelected()).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()),advanced.isSelected()))) {
            if(!busy&&!connected)systemProxy.setSelected(systemProxySupported&&proxy.isSelected());
            applyMode();
        }
    }
    private static void addOption(JPanel content,JCheckBox option) {
        option.setOpaque(false);option.setAlignmentX(Component.LEFT_ALIGNMENT);content.add(option);content.add(Box.createVerticalStrut(6));
    }
    private void applyMode() {
        boolean advanced=actions.settings().advancedMode();
        enginePanel.setVisible(advanced);proxyControls.setVisible(advanced||!systemProxySupported);
        serverDetails.setSelected(advanced);serverDetails.setText(advanced?"Hide server details":"Show server details");
        selectedExplanation.setVisible(advanced);speedProgress.setVisible(advanced);
        servers.repaint();revalidate();repaint();
    }

    public void setServers(List<Server> list) {
        all=List.copyOf(list); searchText.clear(); countryNames.clear(); countryCodes.clear();
        for(Server server:all){
            String name=server.countryName(); countryNames.put(server,name);
            searchText.put(server,(name+" "+server.displayName()+" "+server.host()+" "+source(server)+" "+server.protocolName()+" "+server.protocol()).toLowerCase(Locale.ROOT));
            countryCodes.putIfAbsent(name,server.country());
        }
        Object previous=country.getSelectedItem();
        java.util.ArrayList<String> locations=new java.util.ArrayList<>();locations.add("All locations");locations.addAll(countryCodes.keySet().stream().sorted().toList());
        updatingFilters=true;
        try {
            country.setModel(new DefaultComboBoxModel<>(locations.toArray(String[]::new)));
            country.setSelectedItem(locations.contains(previous)?previous:"All locations");
        } finally {updatingFilters=false;}
        filter();
    }
    private static String source(Server server){return server.sourceName();}
    private ServerPerformance performanceFor(Server server) {
        return performance.getOrDefault(server, UNTESTED);
    }
    private int performanceRank(Server server) {
        return switch(performanceFor(server).state()) {
            case VERIFIED -> 0;
            case TESTING -> 1;
            case UNTESTED -> 2;
            case FAILED -> 3;
        };
    }
    private void filter(){
        Server previous=servers.getSelectedValue();
        JViewport viewport=(JViewport)servers.getParent(); Point view=viewport.getViewPosition();
        String q=search.getText().strip().toLowerCase(Locale.ROOT); Object c=country.getSelectedItem();
        Comparator<Server> ordering = sort.getSelectedIndex()==0
                ? Comparator.comparingInt(this::performanceRank).thenComparing(this::performanceFor,ServerPerformance.bestFirst()).thenComparing(countryNames::get)
                : Comparator.comparing(countryNames::get);
        List<Server> visible=all.stream().filter(s -> (c==null||c.equals("All locations")||c.equals(countryNames.get(s))) && searchText.get(s).contains(q)).sorted(ordering).toList();
        rebuilding=true;
        try {
            model.replace(visible);
            if(previous!=null)servers.setSelectedValue(previous,false);
            if(servers.getSelectedIndex()<0&&!model.isEmpty())servers.setSelectedIndex(0);
            viewport.setViewPosition(new Point(view.x,Math.max(0,Math.min(view.y,model.size()*servers.getFixedCellHeight()-viewport.getHeight()))));
        } finally { rebuilding=false; }
        Server selected=servers.getSelectedValue();
        if(!connected)showSelection(selected);
        count.setText(model.size()+" servers"); updateDiscovery(); servers.repaint(); updateControls();
    }
    public void setPerformance(Server server,ServerPerformance value){performance.put(server,value);if(!performanceRefresh.isRunning())performanceRefresh.start();}
    public void clearPerformance(){performance.clear();performanceRefresh.stop();filter();}
    public void setSpeedTesting(boolean running,String progress){speedTesting=running;retest.setText(running?"Stop testing":"Retest speeds");retest.setToolTipText(running?"Stop testing and keep completed results. Automatic checks resume missing results on the next launch.":"Retest every server, including cached results. Use after changing networks or routes.");speedProgress.setText(progress!=null&&!progress.isBlank()?progress:running?"Testing latency · progress saved…":"Latency · 7-day cache");updateDiscovery();updateControls();}
    public void setCatalogRefreshing(boolean running,boolean saved) {
        catalogRefreshing=running;savedCatalog=saved;
        refresh.setToolTipText(running?"Refreshing servers in the background…":"Refresh servers");
        updateDiscovery();updateControls();
    }
    public void setUpdateStatus(String text,boolean running){
        updateBusy=running;
        updateStatus.setText(text==null?"":text);
        updateStatus.setToolTipText(text);
        updateAction(updateVersion!=null?"Restart to update":running?"Checking for updates…":"Check for updates");
        update.setEnabled(!updateBusy);
    }
    public void setUpdateReady(String version){
        updateVersion=version==null||version.isBlank()?null:version;
        updateBusy=false;
        updateStatus.setText(updateVersion==null?"":"v"+updateVersion+" ready");
        updateStatus.setToolTipText(updateVersion==null?null:"Moondrop "+updateVersion+" is downloaded. Restart to install it.");
        updateAction(updateVersion==null?"Check for updates":"Restart to update");
        update.setToolTipText(updateVersion==null?"Check GitHub for a newer Moondrop release":"Restart Moondrop to install the downloaded update");
        update.setEnabled(true);
    }
    public void setNativeUpdateReady(String version){
        setUpdateReady(version);updateAction("Download update");
        update.setToolTipText("Open the official release to update Moondrop and its bundled Java together.");
    }
    public void selectServer(Server server){
        if(server==null)return;
        search.setText(""); country.setSelectedIndex(0); servers.setSelectedValue(server,true);
        showSelection(server);
    }
    private void showSelection(Server server) {
        selectedName.setText(server==null?"Choose a server":server.countryName());
        selectedName.setIcon(server==null?null:FlagIcons.get(server.country()));selectedName.setIconTextGap(10);
        selectedDetail.setText(server==null?"Select a location from the list.":server.displayName());
        selectedProtection.setVisible(server!=null);serverDetails.setVisible(server!=null);
        if(server!=null) {
            selectedProtocol.setText(server.protocolName());selectedStrength.setText(server.protectionLabel());
            ServerPerformance result=performanceFor(server);
            String measurements=result.completed()?result.label()+" · checked "+DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(result.checkedAt()):result.label();
            selectedExplanation.setText("<html><div style='width:260px'>"+escape(server.protectionDetail())+"<br><br>"+escape(measurements)+"</div></html>");
            selectedProtocol.setToolTipText(server.protectionDetail());selectedStrength.setToolTipText(server.protectionDetail());
        }
    }
    public void setSystemProxySupported(boolean supported){systemProxySupported=supported;systemProxy.setSelected(supported&&actions.settings().systemProxy());systemProxy.setToolTipText(supported?null:"System proxy settings are available on macOS and Windows. Use the local SOCKS5 endpoint on this platform.");applyMode();updateControls();}
    public void setBusy(boolean value){busy=value; updateControls();}
    public void setCoreReady(boolean value){coreReady=value; install.setText(value?"Engine ready":"Install engine"); updateControls();}
    public void setConnection(String state,String description,boolean value){boolean wasConnected=connected;if(wasConnected&&!value)systemProxy.setSelected(systemProxySupported&&actions.settings().systemProxy());connected=value;if(wasConnected&&!value)showSelection(servers.getSelectedValue());status.setText(state);status.setForeground(value?MINT:TEXT);detail.setText("<html><div style='width:270px'>"+escape(description)+"</div></html>");detail.setToolTipText(description);updateControls();}
    public void showError(String title,String description){showFailure(title,description,false);}
    public void showConnectionError(String title,String description){showFailure(title,description,true);}
    private void showFailure(String title,String description,boolean connectionFailure){
        JTextArea text=new JTextArea(description,7,48);text.setEditable(false);text.setLineWrap(true);text.setWrapStyleWord(true);text.setOpaque(false);
        String[] options=connectionFailure?new String[]{"Try another server","Retest servers","Copy diagnostics","Close"}:new String[]{"Copy diagnostics","Close"};
        int choice=JOptionPane.showOptionDialog(this,text,title,JOptionPane.DEFAULT_OPTION,JOptionPane.ERROR_MESSAGE,null,options,options[0]);
        // Defer recovery until the current controller operation has cleared its busy state.
        if(connectionFailure&&choice==0)SwingUtilities.invokeLater(() -> actions.quickConnect(systemProxy.isSelected()));
        else if(connectionFailure&&choice==1)SwingUtilities.invokeLater(actions::forceRetestSpeeds);
        else if(choice==(connectionFailure?2:0))actions.copyDiagnostics();
    }
    private void updateDiscovery(){
        long checked=all.stream().map(this::performanceFor).filter(ServerPerformance::completed).count();
        long working=all.stream().map(this::performanceFor).filter(result -> result.successful()&&result.fresh()).count();
        String message=actions.settings().enabledProtocols().isEmpty()?"Enable a protocol in Settings → Advanced mode.":all.isEmpty()?(catalogRefreshing?"Downloading server list…":"No servers available yet. Use Refresh to try again."):speedTesting&&working==0?"Finding your fastest connection… "+checked+" checked":
                speedTesting?working+" working · "+checked+" checked · testing continues":
                checked>0?working+" working · saved tests reused for 7 days":"Connect to find a working server, or start testing.";
        String cacheNote=catalogRefreshing&&!all.isEmpty()?"Saved servers ready · refreshing list…<br>":savedCatalog?"Using saved servers · refresh unavailable<br>":"";
        discovery.setText("<html><div style='width:260px'>"+cacheNote+escape(message)+"</div></html>");
    }
    public void appendLog(String text){log.append(text+"\n");log.setCaretPosition(log.getDocument().getLength());}
    private void updateControls(){
        retest.setEnabled(speedTesting||(!busy&&!all.isEmpty())); sort.setEnabled(!busy);
        settingsButton.setEnabled(!busy); refresh.setEnabled(!busy&&!catalogRefreshing); install.setEnabled(!busy&&!coreReady&&!connected);
        quickConnect.setText(busy?"Cancel":connected?"Disconnect":"Connect");
        quickConnect.setEnabled(busy||connected||!all.isEmpty());
        automaticHint.setText(connected?"Connected server verified with HTTPS":busy?"Checking the connection…":"Automatically chooses the best tested server");
        connect.setText(busy?"Cancel":connected?"Disconnect":"Connect selected server");
        connect.setEnabled(!busy&&!connected&&servers.getSelectedValue()!=null);
        connect.setVisible(!busy&&!connected);
        systemProxy.setEnabled(systemProxySupported&&!busy&&!connected);
        servers.setEnabled(!busy&&!connected); search.setEnabled(!busy&&!connected); country.setEnabled(!busy&&!connected);
    }
    /** Bounded filter controls keep their shape as the server browser grows. */
    static JPanel serverFilters(JTextField search,JComboBox<String> country,JComboBox<String> sort) {
        JPanel fields=vertical();fields.add(label("EXPLORE LOCATIONS",MUTED,11));fields.add(Box.createVerticalStrut(13));
        search.putClientProperty("JTextField.placeholderText","Search country, city or server");
        search.putClientProperty("FlatLaf.style","background: #202c37; foreground: #edf0e7; borderColor: #3b4b57; focusedBorderColor: #9de5c1; arc: 10");
        search.setAlignmentX(Component.LEFT_ALIGNMENT);search.setPreferredSize(new Dimension(640,38));search.setMaximumSize(new Dimension(640,38));
        search.getAccessibleContext().setAccessibleName("Search servers");fields.add(search);fields.add(Box.createVerticalStrut(8));
        JPanel dropdowns=new JPanel(new GridLayout(1,2,8,0));dropdowns.setOpaque(false);dropdowns.setAlignmentX(Component.LEFT_ALIGNMENT);
        dropdowns.setPreferredSize(new Dimension(640,38));dropdowns.setMaximumSize(new Dimension(640,38));
        String style="background: #202c37; foreground: #edf0e7; buttonBackground: #202c37; buttonArrowColor: #94a4ae; popupBackground: #202c37; borderColor: #3b4b57; focusedBorderColor: #9de5c1; arc: 10; selectionArc: 8; popupInsets: 4,4,4,4; selectionInsets: 0,2,0,2";
        for(JComboBox<String> combo:List.of(country,sort)) {
            combo.putClientProperty("FlatLaf.style",style);combo.setMinimumSize(new Dimension(0,38));combo.setMaximumRowCount(8);dropdowns.add(combo);
        }
        country.getAccessibleContext().setAccessibleName("Filter by location");sort.getAccessibleContext().setAccessibleName("Sort servers");
        fields.add(dropdowns);
        int height=fields.getPreferredSize().height;
        fields.setPreferredSize(new Dimension(640,height));fields.setMaximumSize(new Dimension(640,height));fields.setMinimumSize(new Dimension(0,height));
        JPanel strip=new JPanel();strip.setOpaque(false);strip.setLayout(new BoxLayout(strip,BoxLayout.X_AXIS));strip.add(fields);strip.add(Box.createHorizontalGlue());return strip;
    }
    private static JButton iconButton(String icon,String name) {
        JButton button=new JButton(new FlatSVGIcon("icons/"+icon+".svg",20,20));
        button.setPreferredSize(new Dimension(38,38));
        button.setMargin(new Insets(8,8,8,8));
        button.setToolTipText(name);
        button.getAccessibleContext().setAccessibleName(name);
        button.setActionCommand(name);
        return button;
    }
    private void updateAction(String name) {
        update.setToolTipText(name);
        update.getAccessibleContext().setAccessibleName(name);
        update.setActionCommand(name);
    }
    private static String escape(String value){return value==null?"":value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    private static JPanel panel(LayoutManager layout){JPanel p=new JPanel(layout);p.setBackground(PANEL);p.setBorder(new EmptyBorder(22,22,22,22));p.putClientProperty("FlatLaf.style","arc: 20");return p;}
    private static JPanel vertical(){JPanel p=new JPanel();p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));p.setOpaque(false);return p;}
    private static JLabel label(String text,Color color,int size){JLabel l=new JLabel(text);l.setForeground(color);l.setFont(new Font(Font.SANS_SERIF,size>=19?Font.BOLD:Font.PLAIN,size));l.setAlignmentX(Component.LEFT_ALIGNMENT);return l;}
    private final class ServerRenderer extends JPanel implements ListCellRenderer<Server>{
        private final JLabel title=label("",TEXT,14), sub=label("",MUTED,11), icon=label("",MINT,12), latency=label("",MINT,11), protocol=new ProtocolBadge();
        ServerRenderer(){setLayout(new BorderLayout(10,0));setBorder(new EmptyBorder(8,10,8,10)); JPanel t=vertical();t.add(title);t.add(Box.createVerticalStrut(4));t.add(sub);t.add(Box.createVerticalStrut(5));t.add(protocol);add(icon,BorderLayout.WEST);add(t,BorderLayout.CENTER);latency.setHorizontalAlignment(SwingConstants.RIGHT);add(latency,BorderLayout.EAST);}
        public Component getListCellRendererComponent(JList<? extends Server> list,Server s,int index,boolean selected,boolean focus){setBackground(selected?new Color(44,66,65):PANEL);icon.setText(null);icon.setIcon(FlagIcons.get(s.country()));title.setText(s.countryName());sub.setText(actions.settings().advancedMode()?source(s)+" · "+s.displayName():s.displayName());protocol.setText(s.protocolName());
            ServerPerformance result=performanceFor(s); latency.setText(actions.settings().advancedMode()?result.label():simpleResult(result));
            latency.setForeground(result.successful()?(result.fresh()?MINT:MUTED):result.state()==ServerPerformance.State.FAILED?new Color(220,143,136):MUTED);
            String tooltip=result.state()==ServerPerformance.State.UNTESTED?"Latency has not been tested yet":result.state()==ServerPerformance.State.TESTING?"Checking verified HTTPS round-trip latency…": "Latency checked "+DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(result.checkedAt())+(result.fresh()?" · cached":" · saved, awaiting recheck")+". "+(result.attempts()>0?result.successes()+"/"+result.attempts()+" HTTPS checks succeeded; latency is the median of successful checks. ":"")+(result.bytesPerSecond()>0?"Download estimate "+String.format(Locale.ROOT,"%.1f Mb/s",result.bytesPerSecond()*8.0/1_000_000):result.bytesPerSecond()==0?"Download test failed.":"Download has not been measured.");
            setToolTipText(tooltip);return this;}
    }
    static String simpleResult(ServerPerformance result) {
        String metric=switch(result.state()) {case VERIFIED -> result.millis()+" ms";case FAILED -> "Unavailable";case TESTING -> "Testing…";case UNTESTED -> "Not tested";};
        if(!result.completed())return metric;
        long minutes=Math.max(0,java.time.Duration.between(result.checkedAt(),java.time.Instant.now()).toMinutes());
        String age=minutes<1?"just now":minutes<60?minutes+"m ago":minutes<1440?minutes/60+"h ago":minutes/1440+"d ago";
        return "<html><div style='text-align:right'>"+metric+"<br>"+age+"</div></html>";
    }
    private static final class ProtocolBadge extends JLabel {
        ProtocolBadge(){setFont(new Font(Font.SANS_SERIF,Font.BOLD,10));setForeground(new Color(212,233,223));setBorder(new EmptyBorder(3,7,3,7));setAlignmentX(Component.LEFT_ALIGNMENT);}
        @Override public Dimension getMaximumSize(){return getPreferredSize();}
        @Override protected void paintComponent(Graphics g){
            Graphics2D p=(Graphics2D)g.create();p.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            p.setColor(new Color(37,58,53));p.fillRoundRect(0,0,getWidth(),getHeight(),8,8);
            p.setColor(new Color(71,96,82));p.drawRoundRect(0,0,getWidth()-1,getHeight()-1,8,8);p.dispose();super.paintComponent(g);
        }
    }
    /** Replaces a full feed in a bounded number of events, rather than one event per row. */
    static final class ServerListModel extends AbstractListModel<Server> {
        private List<Server> rows=List.of();
        public int getSize(){return rows.size();}
        public Server getElementAt(int index){return rows.get(index);}
        int size(){return rows.size();}
        boolean isEmpty(){return rows.isEmpty();}
        void replace(List<Server> next){
            if(rows.equals(next))return;
            int oldSize=rows.size();rows=List.copyOf(next);
            int common=Math.min(oldSize,rows.size());
            if(common>0)fireContentsChanged(this,0,common-1);
            if(rows.size()>oldSize)fireIntervalAdded(this,oldSize,rows.size()-1);
            else if(rows.size()<oldSize)fireIntervalRemoved(this,rows.size(),oldSize-1);
        }
    }
    private static final class Mark extends JComponent {
        Mark(){setPreferredSize(new Dimension(40,44));}
        protected void paintComponent(Graphics g){Graphics2D p=(Graphics2D)g.create();p.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);p.setColor(MINT);p.fillOval(2,3,35,35);p.setColor(BG);p.fillOval(15,-3,29,32);p.dispose();}
    }
}
