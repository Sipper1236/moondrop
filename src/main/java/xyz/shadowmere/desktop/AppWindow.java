package xyz.shadowmere.desktop;

import javax.swing.*;
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
        void refresh();
        void retestSpeeds();
        void checkForUpdates();
        void restartToUpdate();
        void quickConnect(boolean systemProxy);
        void connect(Server server, boolean systemProxy);
        void disconnect();
        void installCore();
        void openDataFolder();
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
                String message = all.isEmpty() ? "Refresh to discover available servers" : "No servers match your search";
                graphics.drawString(message, Math.max(12, (getWidth()-graphics.getFontMetrics().stringWidth(message))/2), 48);
            }
        }
    };
    private final JTextField search = new JTextField();
    private final JComboBox<String> country = new JComboBox<>(new String[]{"All locations"});
    private final Map<Server, ServerPerformance> performance = new HashMap<>();
    private final JComboBox<String> sort = new JComboBox<>(new String[]{"Fastest first", "Country name"});
    private final JButton retest = new JButton("Retest speeds");
    private final JButton update = new JButton("Check for updates");
    private final JLabel updateStatus = label("", MUTED, 11);
    private boolean updateBusy;
    private String updateVersion;
    private final JLabel speedProgress = label("Latency · cached for 30 min", MUTED, 11);
    private final Timer performanceRefresh = new Timer(150, e -> filter());
    private boolean speedTesting, rebuilding;
    private final JLabel count = label("Refresh to discover servers", MUTED, 12);
    private final JLabel status = label("Disconnected", TEXT, 28);
    private final JLabel detail = label("Your connection is unchanged.", MUTED, 13);
    private final JLabel selectedName = label("Choose a server", TEXT, 19);
    private final JLabel selectedDetail = label("Select a location from the list.", MUTED, 12);
    private final JButton quickConnect = new JButton("Quick Connect");
    private final JButton connect = new JButton("Connect selected server"), refresh = new JButton("Refresh servers"), install = new JButton("Install engine");
    private final JCheckBox systemProxy = new JCheckBox("Use as system proxy");
    private final JTextArea log = new JTextArea();
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
        header.add(brand,BorderLayout.WEST); refresh.addActionListener(e -> actions.refresh()); JPanel headerTools=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));headerTools.setOpaque(false);updateStatus.setPreferredSize(new Dimension(125,30));headerTools.add(updateStatus);update.setMargin(new Insets(5,8,5,8));update.setToolTipText("Check GitHub for a newer Moondrop release");update.addActionListener(e -> {if(updateVersion!=null)actions.restartToUpdate();else actions.checkForUpdates();});headerTools.add(update);headerTools.add(refresh);header.add(headerTools,BorderLayout.EAST); root.add(header,BorderLayout.NORTH);
        JPanel body = new JPanel(new GridLayout(1,2,26,0)); body.setOpaque(false); root.add(body,BorderLayout.CENTER);
        JPanel browser = panel(new BorderLayout(0,14)); body.add(browser);
        JPanel filters = vertical(); filters.add(label("EXPLORE LOCATIONS",MUTED,11)); filters.add(Box.createVerticalStrut(13));
        search.putClientProperty("JTextField.placeholderText","Search country, city or server"); search.setMaximumSize(new Dimension(Integer.MAX_VALUE,38)); filters.add(search); filters.add(Box.createVerticalStrut(8)); country.setMaximumSize(new Dimension(Integer.MAX_VALUE,36));
        country.setRenderer((list,value,index,selected,focus) -> {
            JLabel item=(JLabel)new DefaultListCellRenderer().getListCellRendererComponent(list,value,index,selected,focus);
            item.setBorder(new EmptyBorder(5,8,5,8));item.setIconTextGap(9);
            String code=countryCodes.get(value);if(code!=null)item.setIcon(FlagIcons.get(code));
            return item;
        });filters.add(country); filters.add(Box.createVerticalStrut(8)); sort.setMaximumSize(new Dimension(Integer.MAX_VALUE,36)); filters.add(sort); sort.addActionListener(e -> filter()); browser.add(filters,BorderLayout.NORTH);
        servers.setBackground(PANEL); servers.setForeground(TEXT); servers.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); servers.setFixedCellHeight(69); servers.setFixedCellWidth(280); servers.setCellRenderer(new ServerRenderer());
        JScrollPane scroll = new JScrollPane(servers); scroll.setBorder(BorderFactory.createEmptyBorder()); scroll.getViewport().setBackground(PANEL); browser.add(scroll,BorderLayout.CENTER);
        JPanel listFooter = vertical(); JPanel counts = new JPanel(new BorderLayout(8,0)); counts.setOpaque(false); counts.add(count,BorderLayout.CENTER); retest.setMargin(new Insets(5,8,5,8)); retest.addActionListener(e -> actions.retestSpeeds()); counts.add(retest,BorderLayout.EAST); counts.setAlignmentX(Component.LEFT_ALIGNMENT); listFooter.add(counts); listFooter.add(Box.createVerticalStrut(6)); listFooter.add(speedProgress); speedProgress.setToolTipText("Verified HTTPS round-trip latency in milliseconds; lower is better. Successful results stay cached for 30 minutes; failures for 5 minutes."); browser.add(listFooter,BorderLayout.SOUTH);
        JPanel right = new JPanel(new BorderLayout(0,18)); right.setOpaque(false); body.add(right);
        JPanel connection = panel(new BorderLayout(0,10)); connection.setBorder(new EmptyBorder(16,20,16,20)); right.add(connection,BorderLayout.NORTH);
        JPanel state = vertical(); state.add(label("YOUR CONNECTION",MUTED,11)); state.add(Box.createVerticalStrut(10)); state.add(status); state.add(Box.createVerticalStrut(8)); state.add(detail); connection.add(state,BorderLayout.NORTH);
        JPanel chosen = vertical(); chosen.add(selectedName); chosen.add(Box.createVerticalStrut(5)); chosen.add(selectedDetail); chosen.add(Box.createVerticalStrut(14));
        systemProxy.setOpaque(false); systemProxy.setForeground(TEXT); systemProxy.setAlignmentX(Component.LEFT_ALIGNMENT); chosen.add(systemProxy); chosen.add(Box.createVerticalStrut(6)); chosen.add(label("Off: connect apps to SOCKS5 127.0.0.1:17890",MUTED,11)); chosen.add(Box.createVerticalStrut(5)); chosen.add(label("On: supported apps use your system proxy settings.",MUTED,11)); connection.add(chosen,BorderLayout.CENTER);
        connect.setPreferredSize(new Dimension(200,44)); connect.setBackground(new Color(43,58,67)); connect.setForeground(TEXT); connect.setFont(connect.getFont().deriveFont(Font.BOLD,14)); connect.addActionListener(e -> {if(connected || busy) actions.disconnect(); else if(servers.getSelectedValue()!=null) actions.connect(servers.getSelectedValue(),systemProxy.isSelected());}); JPanel connectionButtons = vertical();
        quickConnect.setBackground(MINT); quickConnect.setForeground(BG); quickConnect.setFont(quickConnect.getFont().deriveFont(Font.BOLD,21)); quickConnect.setAlignmentX(Component.LEFT_ALIGNMENT); quickConnect.setMaximumSize(new Dimension(Integer.MAX_VALUE,84)); quickConnect.setMinimumSize(new Dimension(250,84)); quickConnect.setPreferredSize(new Dimension(350,84)); quickConnect.putClientProperty("FlatLaf.style","arc: 20");quickConnect.setToolTipText("Automatically test servers and connect to the fastest verified result");quickConnect.addActionListener(e -> actions.quickConnect(systemProxy.isSelected()));
        connectionButtons.add(quickConnect); connectionButtons.add(Box.createVerticalStrut(9)); connectionButtons.add(label("Automatically connects to the fastest tested server", MUTED,11)); connectionButtons.add(Box.createVerticalStrut(10)); connect.setAlignmentX(Component.LEFT_ALIGNMENT); connect.setMaximumSize(new Dimension(Integer.MAX_VALUE,40)); connectionButtons.add(connect); connection.add(connectionButtons,BorderLayout.SOUTH);
        JPanel engine = panel(new BorderLayout(0,10)); engine.setBorder(new EmptyBorder(14,20,14,20)); right.add(engine,BorderLayout.CENTER);
        JPanel engineHeader = new JPanel(new BorderLayout()); engineHeader.setOpaque(false); engineHeader.add(label("ENGINE & ACTIVITY",MUTED,11),BorderLayout.WEST); install.addActionListener(e -> actions.installCore()); engineHeader.add(install,BorderLayout.EAST); engine.add(engineHeader,BorderLayout.NORTH);
        log.setEditable(false); log.setLineWrap(true); log.setWrapStyleWord(true); log.setBackground(PANEL); log.setForeground(MUTED); log.setFont(new Font(Font.MONOSPACED,Font.PLAIN,11)); JScrollPane logs = new JScrollPane(log); logs.setBorder(BorderFactory.createEmptyBorder()); logs.setPreferredSize(new Dimension(300,120)); engine.add(logs,BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout()); footer.setOpaque(false); footer.add(label("Community servers · Availability and privacy vary by operator",MUTED,11),BorderLayout.WEST); JButton folder = new JButton("App files"); folder.addActionListener(e -> actions.openDataFolder()); footer.add(folder,BorderLayout.EAST); root.add(footer,BorderLayout.SOUTH);
        search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){filter();}public void removeUpdate(DocumentEvent e){filter();}public void changedUpdate(DocumentEvent e){filter();}});
        country.addActionListener(e -> {if(!updatingFilters)filter();}); servers.addListSelectionListener(e -> {if(connected||rebuilding)return; Server s=servers.getSelectedValue(); selectedName.setText(s==null?"Choose a server":s.countryName());selectedName.setIcon(s==null?null:FlagIcons.get(s.country()));selectedName.setIconTextGap(10); selectedDetail.setText(s==null?"Select a location from the list.":s.displayName()); updateControls();}); updateControls();
    }
    public void setServers(List<Server> list) {
        all=List.copyOf(list); searchText.clear(); countryNames.clear(); countryCodes.clear();
        for(Server server:all){
            String name=server.countryName(); countryNames.put(server,name);
            searchText.put(server,(name+" "+server.displayName()+" "+server.host()+" "+source(server)).toLowerCase(Locale.ROOT));
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
                ? Comparator.comparingInt(this::performanceRank).thenComparingLong(s -> performanceFor(s).successful()?performanceFor(s).millis():Long.MAX_VALUE).thenComparing(countryNames::get)
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
        if(!connected){selectedName.setText(selected==null?"Choose a server":selected.countryName()); selectedName.setIcon(selected==null?null:FlagIcons.get(selected.country())); selectedName.setIconTextGap(10); selectedDetail.setText(selected==null?"Select a location from the list.":selected.displayName());}
        count.setText(model.size()+" servers"); servers.repaint(); updateControls();
    }
    public void setPerformance(Server server,ServerPerformance value){performance.put(server,value);if(!performanceRefresh.isRunning())performanceRefresh.start();}
    public void clearPerformance(){performance.clear();performanceRefresh.stop();filter();}
    public void setSpeedTesting(boolean running,String progress){speedTesting=running;retest.setText(running?"Stop testing":"Retest speeds");speedProgress.setText(progress!=null&&!progress.isBlank()?progress:running?"Testing latency…":"Latency · cached for 30 min");updateControls();}
    public void setUpdateStatus(String text,boolean running){
        updateBusy=running;
        updateStatus.setText(text==null?"":text);
        updateStatus.setToolTipText(text);
        update.setToolTipText(text);
        update.setText(updateVersion!=null?"Restart to update":running?"Checking…":"Check for updates");
        update.setEnabled(!updateBusy);
    }
    public void setUpdateReady(String version){
        updateVersion=version==null||version.isBlank()?null:version;
        updateBusy=false;
        updateStatus.setText(updateVersion==null?"":"v"+updateVersion+" ready");
        updateStatus.setToolTipText(updateVersion==null?null:"Moondrop "+updateVersion+" is downloaded. Restart to install it.");
        update.setText(updateVersion==null?"Check for updates":"Restart to update");
        update.setToolTipText(updateVersion==null?"Check GitHub for a newer Moondrop release":"Restart Moondrop to install the downloaded update");
        update.setEnabled(true);
    }
    public void selectServer(Server server){
        if(server==null)return;
        search.setText(""); country.setSelectedIndex(0); servers.setSelectedValue(server,true);
        selectedName.setText(server.countryName()); selectedName.setIcon(FlagIcons.get(server.country())); selectedName.setIconTextGap(10); selectedDetail.setText(server.displayName());
    }
    public void setSystemProxySupported(boolean supported){systemProxySupported=supported;systemProxy.setSelected(supported);systemProxy.setToolTipText(supported?null:"System proxy settings are available on macOS and Windows. Use the local SOCKS5 endpoint on this platform.");updateControls();}
    public void setBusy(boolean value){busy=value; updateControls();}
    public void setCoreReady(boolean value){coreReady=value; install.setText(value?"Engine ready":"Install engine"); updateControls();}
    public void setConnection(String state,String description,boolean value){connected=value;status.setText(state);status.setForeground(value?MINT:TEXT);detail.setText("<html><div style='width:270px'>"+escape(description)+"</div></html>");detail.setToolTipText(description);updateControls();}
    public void showError(String title,String description){JTextArea text=new JTextArea(description,7,48);text.setEditable(false);text.setLineWrap(true);text.setWrapStyleWord(true);text.setOpaque(false);JOptionPane.showMessageDialog(this,text,title,JOptionPane.ERROR_MESSAGE);}
    public void appendLog(String text){log.append(text+"\n");log.setCaretPosition(log.getDocument().getLength());}
    private void updateControls(){
        retest.setEnabled(speedTesting||(!busy&&!all.isEmpty())); sort.setEnabled(!busy);
        refresh.setEnabled(!busy); install.setEnabled(!busy&&!coreReady&&!connected);
        quickConnect.setText(busy?"Please wait…":"Quick Connect");
        quickConnect.setEnabled(!busy&&!connected&&!all.isEmpty());
        connect.setText(busy?"Cancel":connected?"Disconnect":"Connect selected server");
        connect.setEnabled(busy||connected||(servers.getSelectedValue()!=null));
        systemProxy.setEnabled(systemProxySupported&&!busy&&!connected);
        servers.setEnabled(!busy&&!connected); search.setEnabled(!busy&&!connected); country.setEnabled(!busy&&!connected);
    }
    private static String escape(String value){return value==null?"":value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    private static JPanel panel(LayoutManager layout){JPanel p=new JPanel(layout);p.setBackground(PANEL);p.setBorder(new EmptyBorder(22,22,22,22));p.putClientProperty("FlatLaf.style","arc: 20");return p;}
    private static JPanel vertical(){JPanel p=new JPanel();p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));p.setOpaque(false);return p;}
    private static JLabel label(String text,Color color,int size){JLabel l=new JLabel(text);l.setForeground(color);l.setFont(new Font(Font.SANS_SERIF,size>=19?Font.BOLD:Font.PLAIN,size));l.setAlignmentX(Component.LEFT_ALIGNMENT);return l;}
    private final class ServerRenderer extends JPanel implements ListCellRenderer<Server>{
        private final JLabel title=label("",TEXT,14), sub=label("",MUTED,11), icon=label("",MINT,12), latency=label("",MINT,12);
        ServerRenderer(){setLayout(new BorderLayout(12,0));setBorder(new EmptyBorder(10,12,10,12)); JPanel t=vertical();t.add(title);t.add(Box.createVerticalStrut(5));t.add(sub);add(icon,BorderLayout.WEST);add(t,BorderLayout.CENTER);latency.setHorizontalAlignment(SwingConstants.RIGHT);add(latency,BorderLayout.EAST);}
        public Component getListCellRendererComponent(JList<? extends Server> list,Server s,int index,boolean selected,boolean focus){setBackground(selected?new Color(44,66,65):PANEL);icon.setText(null);icon.setIcon(FlagIcons.get(s.country()));title.setText(s.countryName());sub.setText(source(s)+" · "+s.displayName());
            ServerPerformance result=performanceFor(s); latency.setText(result.label());
            latency.setForeground(result.successful()?(result.fresh()?MINT:MUTED):result.state()==ServerPerformance.State.FAILED?new Color(220,143,136):MUTED);
            String tooltip=result.state()==ServerPerformance.State.UNTESTED?"Latency has not been tested yet":result.state()==ServerPerformance.State.TESTING?"Checking verified HTTPS round-trip latency…": "Latency checked "+DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(result.checkedAt())+(result.fresh()?" · cached":" · expired")+". Lower milliseconds is better.";
            setToolTipText(tooltip);return this;}
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
