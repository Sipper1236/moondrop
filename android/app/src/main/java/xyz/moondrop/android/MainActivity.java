package xyz.moondrop.android;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Build;
import android.graphics.Insets;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.*;
import xyz.shadowmere.desktop.Server;
import java.util.*;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Native, accessible Android interface. Credentials never enter visible labels or diagnostics. */
public final class MainActivity extends Activity implements AppController.Listener {
    private static final int BG=0xff121921, CARD=0xff1a232d, MINT=0xff9de5c1,
            TEXT=0xffedf5f1, MUTED=0xff9baeb7, BORDER=0xff30414c, VPN_REQUEST=41, NOTIFICATION_REQUEST=42;
    private static final Set<String> ISO_COUNTRIES=Set.of(Locale.getISOCountries());
    private AppController controller;
    private final AndroidUpdater updater=new AndroidUpdater();
    private AlertDialog settingsDialog;
    private AppState state;
    private TextView status, detail, progress, count, error;
    private Button connect;
    private LinearLayout errorActions, headerBar, connectionCard;
    private EditText search;
    private Spinner country, sort;
    private final ArrayList<Server> visible=new ArrayList<>();
    private ServerAdapter adapter;
    private String chosenCountry="All locations";
    private List<String> countryOptions=List.of();
    private Server pendingServer;
    private String pendingServerKey;
    private boolean permissionPending, updatingCountries;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        controller=AppController.get(getApplicationContext());
        if(savedInstanceState!=null){permissionPending=savedInstanceState.getBoolean("vpn_permission_pending");String key=savedInstanceState.getString("pending_server_key");pendingServerKey=key;if(key!=null)for(Server server:controller.state().servers)if(ServerRepository.key(server).equals(key)){pendingServer=server;break;}}
        LinearLayout root=column(); root.setPadding(dp(20),dp(12),dp(20),0); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            if(Build.VERSION.SDK_INT>=30){
                Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
                view.setPadding(dp(20)+safe.left,dp(12)+safe.top,dp(20)+safe.right,safe.bottom);
            }else{
                view.setPadding(dp(20)+insets.getSystemWindowInsetLeft(),dp(12)+insets.getSystemWindowInsetTop(),dp(20)+insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            }
            boolean keyboard=Build.VERSION.SDK_INT>=30?insets.isVisible(WindowInsets.Type.ime()):insets.getSystemWindowInsetBottom()>dp(120);
            if(headerBar!=null)headerBar.setVisibility(keyboard?View.GONE:View.VISIBLE);
            if(connectionCard!=null)connectionCard.setVisibility(keyboard?View.GONE:View.VISIBLE);
            return insets;
        });
        LinearLayout header=headerBar=row();
        LinearLayout brand=column(); brand.addView(label("MOONDROP",23,TEXT,true)); brand.addView(label("by rayyan",12,MUTED,false));
        header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        header.addView(icon("↻","Refresh servers",v->controller.refresh()));
        header.addView(icon("↓","Check for updates",v->checkForUpdates(true)));
        header.addView(icon("⚙","Settings",v->showSettings())); root.addView(header);
        LinearLayout hero=connectionCard=column(); hero.setPadding(dp(18),dp(18),dp(18),dp(18)); hero.setBackground(shape(CARD,22));
        status=label("Ready to connect",21,TEXT,true); detail=label("Automatically chooses your best tested server",13,MUTED,false);
        hero.addView(status); margin(detail,0,7,0,18); hero.addView(detail);
        connect=new Button(this); connect.setAllCaps(false); connect.setTextSize(18); connect.setTypeface(null,Typeface.BOLD);
        connect.setTextColor(BG); connect.setBackground(shape(MINT,16)); connect.setText("Connect"); connect.setMinHeight(dp(60));
        connect.setOnClickListener(v->{if(state!=null&&(state.connected||state.connecting))controller.disconnect(); else requestConnection(null);});
        hero.addView(connect,new LinearLayout.LayoutParams(-1,dp(60)));
        progress=label("Your saved servers appear immediately",12,MUTED,false); margin(progress,0,12,0,0); hero.addView(progress);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=dp(22);hp.bottomMargin=dp(18);root.addView(hero,hp);
        error=label("",13,0xffffb8ad,false);error.setVisibility(View.GONE);root.addView(error);
        errorActions=row();errorActions.addView(action("Try another",v->requestConnection(null)),new LinearLayout.LayoutParams(0,dp(48),1));errorActions.addView(action("Refresh",v->controller.refresh()),new LinearLayout.LayoutParams(0,dp(48),1));errorActions.addView(action("Copy error",v->copyDiagnostics()),new LinearLayout.LayoutParams(0,dp(48),1));errorActions.setVisibility(View.GONE);root.addView(errorActions);
        LinearLayout listTitle=row(); listTitle.addView(label("Locations",18,TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        count=label("",12,MUTED,false);listTitle.addView(count);root.addView(listTitle);
        search=new EditText(this); search.setSingleLine(true);search.setTextColor(TEXT);search.setHintTextColor(MUTED); search.setTextSize(14);
        search.setHint("Search locations or protocols");search.setContentDescription("Search servers");search.setPadding(dp(14),0,dp(14),0);search.setBackground(shape(CARD,12));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(48));sp.topMargin=dp(12);sp.bottomMargin=dp(8);root.addView(search,sp);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){} public void onTextChanged(CharSequence s,int start,int before,int count){filter();}public void afterTextChanged(Editable e){}});
        LinearLayout filters=row();country=spinner();sort=spinner();
        filters.addView(country,new LinearLayout.LayoutParams(0,dp(48),1));LinearLayout.LayoutParams sr=new LinearLayout.LayoutParams(0,dp(48),1);sr.leftMargin=dp(8);filters.addView(sort,sr);
        sort.setAdapter(new DropAdapter(List.of("Sort: recommended","Sort: country")));root.addView(filters);
        country.setOnItemSelectedListener(new SimpleSelection(){@Override public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(!updatingCountries){chosenCountry=String.valueOf(p.getItemAtPosition(pos));filter();}}});
        sort.setOnItemSelectedListener(new SimpleSelection(){@Override public void onItemSelected(AdapterView<?> p,View v,int pos,long id){filter();}});
        ListView list=new ListView(this);list.setDivider(null);list.setDividerHeight(dp(8));list.setPadding(0,dp(12),0,dp(12));list.setClipToPadding(false);
        adapter=new ServerAdapter();list.setAdapter(adapter);list.setOnItemClickListener((parent,view,pos,id)->showServer(visible.get(pos)));
        root.addView(list,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);root.requestApplyInsets();onState(controller.state());if(controller.preferences().automaticUpdates)checkForUpdates(false);
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putBoolean("vpn_permission_pending",permissionPending);if(pendingServerKey!=null)out.putString("pending_server_key",pendingServerKey);}
    @Override protected void onStart(){super.onStart();controller.addListener(this);}
    @Override protected void onStop(){controller.removeListener(this);super.onStop();}
    @Override public void onState(AppState next){runOnUiThread(()->render(next));}
    private void render(AppState next){
        state=next;
        status.setText(next.title==null?next.connected?"Connected":next.connecting?"Connecting…":"Ready to connect":next.title);
        detail.setText(next.detail==null?"Automatically chooses your best tested server":next.detail);
        connect.setText(next.connecting?"Cancel":next.connected?"Disconnect":"Connect");
        connect.setEnabled(!permissionPending);
        progress.setText(next.testing?"Finding your fastest connection · "+next.checked+" checked · "+next.working+" working":next.loading?"Showing saved servers · refreshing locations…":next.working>0?next.working+" working servers · saved results reused":controller.preferences().singleServerMode()?"Single-server selection · saved rankings · no background scans":"Saved results are reused; new servers are tested automatically");
        error.setText(next.error==null?"":next.error);error.setVisibility(next.error==null||next.error.isBlank()?View.GONE:View.VISIBLE);
        errorActions.setVisibility(error.getVisibility());
        TreeSet<String> names=new TreeSet<>();for(Server server:next.servers)names.add(server.countryName());
        ArrayList<String> options=new ArrayList<>();options.add("All locations");options.addAll(names);
        if(!options.equals(countryOptions)){countryOptions=options;updatingCountries=true;country.setAdapter(new DropAdapter(options));int selected=options.indexOf(chosenCountry);country.setSelection(Math.max(0,selected));if(selected<0)chosenCountry="All locations";updatingCountries=false;}
        filter();
    }
    private void filter(){
        if(state==null||adapter==null)return;
        String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);Preferences prefs=controller.preferences();
        visible.clear();for(Server server:state.servers){
            if(!prefs.enabledProtocols.contains(server.protocol()))continue;
            if(!chosenCountry.equals("All locations")&&!server.countryName().equals(chosenCountry))continue;
            if(!query.isEmpty()&&!(server.countryName()+" "+server.displayName()+" "+server.protocolName()).toLowerCase(Locale.ROOT).contains(query))continue;
            visible.add(server);
        }
        Comparator<Server> byCountry=Comparator.comparing(Server::countryName).thenComparing(Server::displayName);
        visible.sort(sort.getSelectedItemPosition()==1?byCountry:Comparator.<Server>comparingInt(server->{Measurement m=measurement(server);return m!=null&&m.verified()?0:m==null?1:2;}).thenComparingDouble(server->{Measurement m=measurement(server);return m!=null&&m.verified()?m.score():Double.POSITIVE_INFINITY;}).thenComparing(byCountry));
        count.setText(visible.size()+" servers");adapter.notifyDataSetChanged();
    }
    private Measurement measurement(Server server){return state.measurements.get(ServerRepository.key(server));}
    private void requestConnection(Server server){
        if(controller.preferences().singleServerMode()){new AlertDialog.Builder(this).setTitle("Contact one proxy?").setMessage("The entry proxy operator can see your public IP. One cached-ranked exit is attempted; no automatic alternatives. With two-hop routing, traffic goes through the selected Shadowsocks entry before the exit. Operator independence is not verified and traffic correlation remains possible. HTTPS checks use the selected route.").setPositiveButton("Connect one server",(d,w)->requestDisclosedConnection(server)).setNegativeButton("Cancel",null).show();return;}
        requestDisclosedConnection(server);
    }
    private void requestDisclosedConnection(Server server){
        if(permissionPending)return;
        if(state!=null&&(state.connected||state.connecting)){new AlertDialog.Builder(this).setTitle("Switch connection?").setMessage("Disconnect your current connection before choosing another server.").setPositiveButton("Disconnect",(d,w)->controller.disconnect()).setNegativeButton("Keep connection",null).show();return;}
        pendingServer=server;pendingServerKey=server==null?null:ServerRepository.key(server);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED
                &&!getSharedPreferences("settings",MODE_PRIVATE).getBoolean("notificationAsked",false)){
            // A denied notification request never blocks VPN consent or connection.
            getSharedPreferences("settings",MODE_PRIVATE).edit().putBoolean("notificationAsked",true).apply();
            permissionPending=true;connect.setEnabled(false);
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_REQUEST);return;
        }
        prepareVpnPermission();
    }
    private void prepareVpnPermission(){
        Intent permission=VpnService.prepare(this);
        if(permission==null){permissionPending=false;connect.setEnabled(true);startPendingConnection();return;}
        permissionPending=true;connect.setEnabled(false);startActivityForResult(permission,VPN_REQUEST);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==NOTIFICATION_REQUEST)prepareVpnPermission();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==VPN_REQUEST){permissionPending=false;connect.setEnabled(true);if(result==RESULT_OK)startPendingConnection();else{pendingServer=null;pendingServerKey=null;Toast.makeText(this,"VPN permission is needed to connect",Toast.LENGTH_LONG).show();}}else if(request==7341)updater.resumeInstall(this);}
    private void startPendingConnection(){
        Server server=pendingServer;String key=pendingServerKey;pendingServer=null;pendingServerKey=null;
        if(server==null&&key!=null){
            for(Server candidate:controller.state().servers)if(ServerRepository.key(candidate).equals(key)){server=candidate;break;}
            if(server==null){Toast.makeText(this,"That server is no longer available. Choose another server.",Toast.LENGTH_LONG).show();return;}
        }
        if(server==null)controller.connectBest();else controller.connect(server);
    }
    private void showServer(Server server){
        Measurement m=measurement(server);String body=server.protocolName()+" · "+server.protectionLabel()+"\n\n"+quality(m);
        if(controller.preferences().advancedMode)body+="\n\n"+server.protectionDetail()+"\n"+(m==null?"Not tested yet":m.successes+" of "+m.attempts+" checks succeeded");
        new AlertDialog.Builder(this).setTitle(flag(server.country())+"  "+server.countryName()).setMessage(body).setPositiveButton("Connect",(d,w)->requestConnection(server)).setNeutralButton("Test this server",(d,w)->{
            new AlertDialog.Builder(this).setTitle("Test one proxy?").setMessage("The directly contacted entry operator will see your public IP. HTTPS latency and download checks travel through the selected encrypted route. Disconnect your current VPN before testing.").setPositiveButton("Test",(a,b)->controller.testServer(server)).setNegativeButton("Cancel",null).show();
        }).setNegativeButton("Close",null).show();
    }
    private void showSettings(){
        if(settingsDialog!=null&&settingsDialog.isShowing())return;
        LinearLayout box=column();box.setPadding(dp(24),dp(8),dp(24),dp(12));
        ScrollView scroll=new ScrollView(this);scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Settings").setView(scroll).setPositiveButton("Done",null).create();
        settingsDialog=dialog;dialog.setOnDismissListener(d->settingsDialog=null);buildSettings(box,dialog);dialog.show();
    }

    private void checkForUpdates(boolean userInitiated) {
        if(!userInitiated&&!controller.preferences().automaticUpdates)return;
        updater.check((available,failure)->runOnUiThread(()->{
            if(isFinishing())return;
            if(failure!=null){if(userInitiated)Toast.makeText(this,"Could not check GitHub Releases for updates.",Toast.LENGTH_LONG).show();return;}
            if(available==null){if(userInitiated)Toast.makeText(this,"Moondrop is up to date.",Toast.LENGTH_SHORT).show();return;}
            new AlertDialog.Builder(this).setTitle("Moondrop update available")
                    .setMessage("Version "+available.version()+" is available. The APK will be checksum-checked and must match the installed publisher signature before Android offers to install it.")
                    .setPositiveButton("Download",(dialog,which)->downloadUpdate(available)).setNegativeButton("Later",null).show();
        }));
    }

    private void downloadUpdate(AndroidUpdater.Update update) {
        Toast.makeText(this,"Downloading and verifying Moondrop "+update.version()+"…",Toast.LENGTH_LONG).show();
        updater.downloadAndInstall(this,update,(ready,failure)->{
            if(isFinishing())return;
            if(failure!=null){Toast.makeText(this,failure.getMessage(),Toast.LENGTH_LONG).show();return;}
            new AlertDialog.Builder(this).setTitle("Update verified")
                    .setMessage("Android will now ask you to confirm installation.")
                    .setPositiveButton("Install",(dialog,which)->{try{updater.install(this);}catch(Exception problem){Toast.makeText(this,problem.getMessage(),Toast.LENGTH_LONG).show();}})
                    .setNegativeButton("Not now",null).show();
        });
    }

    private void buildSettings(LinearLayout box,AlertDialog dialog){
        box.removeAllViews();Preferences prefs=controller.preferences();
        TextView intro=label("Device VPN · routes other apps through an encrypted proxy. Moondrop itself is excluded so feeds and proxy connections can reach the network.",13,MUTED,false);box.addView(intro);
        toggle(box,"Advanced mode","Show protocols, detailed measurements and diagnostics",prefs.advancedMode,value->{controller.savePreferences(controller.preferences().withAdvancedMode(value));buildSettings(box,dialog);filter();});
        toggle(box,"Privacy mode","Optional: cached ranking, no background scans, one proxy per connection attempt",prefs.privacyMode,value->{controller.savePreferences(controller.preferences().withPrivacyMode(value));buildSettings(box,dialog);});
        toggle(box,"Ask before contacting a proxy","Confirm each connection, try one cached-ranked server, and pause background tests",prefs.confirmProxyContact,value->{controller.savePreferences(controller.preferences().withConfirmProxyContact(value));buildSettings(box,dialog);});
        toggle(box,"Check for updates automatically","Contact GitHub Releases when Moondrop opens; manual update checks remain available",prefs.automaticUpdates,value->controller.savePreferences(controller.preferences().withAutomaticUpdates(value)));
        TextView privacyNote=label(prefs.singleServerMode()?"Automatic tests are paused. Use a server’s Test action to intentionally contact it.":"Automatic testing may contact many proxy operators; each can see your public IP.",12,MUTED,false);box.addView(privacyNote);
        toggle(box,"Test servers automatically","Reuse cached results and test new servers",prefs.automaticTesting,value->controller.savePreferences(controller.preferences().withAutomaticTesting(value)));
        toggle(box,"Nearby locations first","Prioritize your selected country or device region; no IP lookup",prefs.nearbyFirst,value->controller.savePreferences(controller.preferences().withNearbyFirst(value)));
        Button region=new Button(this);region.setAllCaps(false);region.setText("Preferred country: "+(prefs.manualCountry.isEmpty()?"Device region":prefs.manualCountry));region.setOnClickListener(v->{
            EditText input=new EditText(this);input.setSingleLine(true);input.setHint("Two-letter country, e.g. CA (blank = device)");input.setText(controller.preferences().manualCountry);
            new AlertDialog.Builder(this).setTitle("Preferred country").setMessage("Manual selection only. Moondrop Android never contacts an IP geolocation provider.").setView(input).setPositiveButton("Save",(d,w)->{String code=input.getText().toString().trim();if(!code.isEmpty()&&Preferences.normalizeCountry(code).isEmpty()){Toast.makeText(this,"Enter a valid two-letter country code",Toast.LENGTH_LONG).show();return;}controller.savePreferences(controller.preferences().withManualCountry(code));buildSettings(box,dialog);}).setNegativeButton("Cancel",null).show();});box.addView(region);
        Button chain=new Button(this);chain.setAllCaps(false);chain.setText(prefs.entryKey.isEmpty()?"Two-hop routing: off":"Two-hop routing: selected entry");chain.setOnClickListener(v->{
            List<Server> entries=new ArrayList<>();for(Server candidate:controller.state().servers)if(candidate.protocol().equals("shadowsocks"))entries.add(candidate);
            String[] labels=new String[entries.size()+1];labels[0]="Off · connect directly to one exit";for(int i=0;i<entries.size();i++)labels[i+1]=flag(entries.get(i).country())+" "+entries.get(i).countryName()+" · Shadowsocks · "+(i+1);
            new AlertDialog.Builder(this).setTitle("Choose a Shadowsocks entry").setItems(labels,(d,position)->{controller.savePreferences(controller.preferences().withEntryKey(position==0?"":ServerRepository.key(entries.get(position-1))));buildSettings(box,dialog);}).setNegativeButton("Cancel",null).show();});box.addView(chain);
        box.addView(label("Two-hop supports TCP exits (Shadowsocks, VLESS, Trojan). Automatic tests pause. The entry sees your IP; the exit receives traffic through it. Different operators are not verified; correlation remains possible. Cached speeds describe single-hop connections, not the chain.",12,MUTED,false));
        if(prefs.advancedMode){
            TextView protocols=label("Protocols",15,TEXT,true);margin(protocols,0,16,0,4);box.addView(protocols);
            String[][] choices={{"shadowsocks","Shadowsocks","Encrypted proxy"},{"vless","VLESS","TLS / REALITY camouflage"},{"trojan","Trojan","TLS camouflage"},{"hysteria2","Hysteria 2","Encrypted QUIC"},{"tuic","TUIC","TLS over QUIC"}};
            for(String[] choice:choices)toggle(box,choice[1],choice[2],prefs.enabledProtocols.contains(choice[0]),value->{Set<String> enabled=new HashSet<>(controller.preferences().enabledProtocols);if(value)enabled.add(choice[0]);else enabled.remove(choice[0]);controller.savePreferences(controller.preferences().withEnabledProtocols(enabled));filter();});
            TextView note=label("Protocol changes apply to future connections. Your current connection stays active.",12,MUTED,false);box.addView(note);
            Button diagnostics=new Button(this);diagnostics.setText("Copy diagnostics");diagnostics.setAllCaps(false);diagnostics.setOnClickListener(v->copyDiagnostics());box.addView(diagnostics);
        }
        box.addView(label("Always enforced: public-network feed targets, supported modern encrypted protocols, TLS certificate validation, encrypted saved credentials, and APK checksum / publisher-signature verification. These protections cannot be disabled.",12,MUTED,false));
        box.addView(label("Android controls fail-closed blocking. Enable Always-on VPN and Block connections without VPN in Android’s VPN settings to stop other apps from falling back to a direct connection.",12,MUTED,false));
        Button strict=new Button(this);strict.setText("Device VPN & strict blocking");strict.setAllCaps(false);strict.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Android strict blocking").setMessage("Connect successfully once, then open Android Settings → VPN → Moondrop and enable Always-on VPN plus Block connections without VPN. Android enforces blocking for other apps when the tunnel stops; Moondrop restores only the exact previously approved pinned route, without trying other servers. If restoration fails, open Moondrop and choose another connection. Moondrop is excluded from its tunnel for transport/feed access; Android may block its feed refreshes while lockdown is active. Turning off Android’s blocking setting allows normal direct traffic again.").setPositiveButton("Open VPN settings",(d,w)->{try{startActivity(new Intent("android.settings.VPN_SETTINGS"));}catch(RuntimeException unavailable){Toast.makeText(this,"Open VPN settings in Android Settings",Toast.LENGTH_LONG).show();}}).setNegativeButton("Close",null).show());box.addView(strict);
        Button paste=new Button(this);paste.setText("Import server links");paste.setAllCaps(false);paste.setOnClickListener(v->{
            EditText input=new EditText(this);input.setHint("Paste ss://, vless://, trojan://, hy2:// or tuic:// links");input.setMinLines(3);input.setMaxLines(8);
            new AlertDialog.Builder(this).setTitle("Import servers offline").setMessage("Paste one encrypted server link per line. No download or connection is made. Public endpoints only; private network profiles are rejected. The server operator sees your IP when you connect.").setView(input).setPositiveButton("Import",(d,w)->{try{int count=controller.importProfiles(input.getText().toString());Toast.makeText(this,"Imported "+count+" servers",Toast.LENGTH_LONG).show();filter();}catch(IllegalArgumentException failure){Toast.makeText(this,failure.getMessage(),Toast.LENGTH_LONG).show();}}).setNegativeButton("Cancel",null).show();
        });box.addView(paste);
        Button erase=new Button(this);erase.setText("Erase saved profiles & test history");erase.setAllCaps(false);erase.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Erase saved data?").setMessage("Deletes saved proxy credentials and speed history. Your active VPN stays connected until you disconnect. Refresh later to load profiles again.").setPositiveButton("Erase",(d,w)->controller.eraseSavedProfiles()).setNegativeButton("Cancel",null).show());box.addView(erase);
        Button licenses=new Button(this);licenses.setText("Open-source licenses");licenses.setAllCaps(false);licenses.setOnClickListener(v->showLicenses());box.addView(licenses);
    }
    private void showLicenses(){
        TextView text=label(readLegalAsset("LICENSE")+"\n\n"+readLegalAsset("THIRD_PARTY.md"),12,TEXT,false);
        text.setTextIsSelectable(true);text.setPadding(dp(22),dp(12),dp(22),dp(12));
        ScrollView scroll=new ScrollView(this);scroll.addView(text);
        new AlertDialog.Builder(this).setTitle("Open-source licenses").setView(scroll).setPositiveButton("Done",null).show();
    }
    private String readLegalAsset(String name){
        try(InputStream input=getAssets().open(name);ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int length;while((length=input.read(buffer))!=-1)output.write(buffer,0,length);
            return name+"\n\n"+new String(output.toByteArray(),StandardCharsets.UTF_8);
        }catch(java.io.IOException missing){return name+"\nLicense file is unavailable in this installation.";}
    }
    private void copyDiagnostics(){ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("Moondrop diagnostics",controller.diagnostics()));Toast.makeText(this,"Diagnostics copied",Toast.LENGTH_SHORT).show();}
    private Button action(String text,View.OnClickListener listener){Button button=new Button(this);button.setText(text);button.setTextSize(12);button.setAllCaps(false);button.setTextColor(MINT);button.setBackgroundColor(Color.TRANSPARENT);button.setPadding(0,0,0,0);button.setOnClickListener(listener);return button;}
    private interface Changed{void changed(boolean value);}
    private void toggle(LinearLayout parent,String title,String hint,boolean checked,Changed action){
        LinearLayout line=row();line.setPadding(0,dp(10),0,dp(10));LinearLayout text=column();text.addView(label(title,15,TEXT,false));text.addView(label(hint,12,MUTED,false));line.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        Switch control=new Switch(this);control.setContentDescription(title);control.setThumbTintList(ColorStateList.valueOf(MINT));control.setChecked(checked);control.setOnCheckedChangeListener((button,value)->action.changed(value));line.addView(control);parent.addView(line);
    }
    private String quality(Measurement m){
        if(m==null)return "Not tested yet";if(!m.verified())return "Unavailable · "+age(m.checkedAt);
        String value=m.latencyMs+" ms";
        if(controller.preferences().advancedMode&&m.bytesPerSecond>0)value+=" · "+String.format(Locale.ROOT,"%.1f Mbps",m.bytesPerSecond*8.0/1_000_000);
        return value+(controller.preferences().entryKey.isEmpty()?"":" · single-hop cache")+" · "+age(m.checkedAt)+(m.fresh()?"":" · needs recheck");
    }
    static String flag(String country){
        if(country==null||!country.matches("(?i)[a-z]{2}")||!ISO_COUNTRIES.contains(country.toUpperCase(Locale.ROOT)))return "🏳️?";
        String code=country.toUpperCase(Locale.ROOT);return new String(Character.toChars(0x1f1e6+code.charAt(0)-'A'))+new String(Character.toChars(0x1f1e6+code.charAt(1)-'A'));
    }
    static String age(long timestamp){long minutes=Math.max(0,(System.currentTimeMillis()-timestamp)/60000);return minutes<1?"just checked":minutes<60?minutes+"m ago":minutes<1440?minutes/60+"h ago":minutes/1440+"d ago";}
    private final class ServerAdapter extends BaseAdapter {
        public int getCount(){return visible.size();}public Server getItem(int p){return visible.get(p);}public long getItemId(int p){return p;}
        public View getView(int position,View reuse,ViewGroup parent){
            LinearLayout card;TextView title,subtitle,badge;
            if(reuse==null){card=column();card.setPadding(dp(16),dp(14),dp(16),dp(14));card.setBackground(shape(CARD,14));LinearLayout top=row();title=label("",15,TEXT,true);badge=label("",11,MINT,false);top.addView(title,new LinearLayout.LayoutParams(0,-2,1));top.addView(badge);card.addView(top);subtitle=label("",12,MUTED,false);margin(subtitle,0,7,0,0);card.addView(subtitle);card.setTag(new TextView[]{title,subtitle,badge});}
            else card=(LinearLayout)reuse;
            TextView[] labels=(TextView[])card.getTag();Server server=getItem(position);boolean active=state.connectedServer!=null&&ServerRepository.key(server).equals(ServerRepository.key(state.connectedServer));
            labels[0].setText(flag(server.country())+"  "+server.countryName()+(active?"  •":""));labels[1].setText(quality(measurement(server)));labels[2].setText(server.protocolName());card.setContentDescription(server.countryName()+", "+server.protocolName()+", "+quality(measurement(server)));return card;
        }
    }
    private final class DropAdapter extends ArrayAdapter<String>{DropAdapter(List<String> options){super(MainActivity.this,android.R.layout.simple_spinner_item,options);setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);}
        @Override public View getView(int p,View v,ViewGroup parent){TextView text=(TextView)super.getView(p,v,parent);text.setTextColor(TEXT);text.setTextSize(12);text.setSingleLine(true);text.setPadding(dp(10),0,dp(4),0);return text;}
        @Override public View getDropDownView(int p,View v,ViewGroup parent){TextView text=(TextView)super.getDropDownView(p,v,parent);text.setTextColor(TEXT);text.setBackgroundColor(CARD);text.setMinHeight(dp(48));return text;}}
    private abstract static class SimpleSelection implements AdapterView.OnItemSelectedListener{public void onNothingSelected(AdapterView<?> parent){}}
    private Spinner spinner(){Spinner view=new Spinner(this);view.setBackground(shape(CARD,10));return view;}
    private Button icon(String symbol,String description,View.OnClickListener click){Button view=new Button(this);view.setText(symbol);view.setTextSize(24);view.setTextColor(MINT);view.setBackgroundColor(Color.TRANSPARENT);view.setContentDescription(description);view.setMinWidth(0);view.setMinimumWidth(0);view.setPadding(0,0,0,0);view.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));view.setOnClickListener(click);return view;}
    private LinearLayout column(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private LinearLayout row(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.HORIZONTAL);view.setGravity(Gravity.CENTER_VERTICAL);return view;}
    private TextView label(String text,int size,int color,boolean bold){TextView view=new TextView(this);view.setText(text);view.setTextSize(size);view.setTextColor(color);if(bold)view.setTypeface(null,Typeface.BOLD);return view;}
    private GradientDrawable shape(int color,int radius){GradientDrawable drawable=new GradientDrawable();drawable.setColor(color);drawable.setCornerRadius(dp(radius));return drawable;}
    private void margin(View view,int l,int t,int r,int b){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.setMargins(dp(l),dp(t),dp(r),dp(b));view.setLayoutParams(params);}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
