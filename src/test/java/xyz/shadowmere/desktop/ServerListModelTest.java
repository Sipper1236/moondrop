package xyz.shadowmere.desktop;

import org.junit.jupiter.api.Test;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class ServerListModelTest {
    @Test void replacesLargeFeedsWithBoundedNotificationsAndSkipsIdenticalOrder() {
        AppWindow.ServerListModel model=new AppWindow.ServerListModel();
        AtomicInteger notifications=new AtomicInteger();
        model.addListDataListener(new ListDataListener(){
            public void contentsChanged(ListDataEvent e){notifications.incrementAndGet();}
            public void intervalAdded(ListDataEvent e){notifications.incrementAndGet();}
            public void intervalRemoved(ListDataEvent e){notifications.incrementAndGet();}
        });
        List<Server> feed=IntStream.range(0,13_000).mapToObj(i -> new Server("gfp:"+i,"host"+i,8388,"aes-128-gcm","test","","","",true)).toList();
        model.replace(feed);
        assertEquals(13_000,model.getSize());
        assertEquals(1,notifications.get());
        model.replace(new ArrayList<>(feed));
        assertEquals(1,notifications.get(),"Latency repaints with unchanged order must not reset the list");
        List<Server> shorter=feed.subList(100,12_000);
        model.replace(shorter);
        assertEquals(3,notifications.get());
        assertEquals(shorter.get(0),model.getElementAt(0));
        assertEquals(shorter.size(),model.getSize());
        model.replace(List.of());
        assertEquals(4,notifications.get());
        assertTrue(model.isEmpty());
    }
}
