package xyz.shadowmere.desktop;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import static org.junit.jupiter.api.Assertions.*;

class FilterLayoutTest {
    @Test void controlsKeepMatchingHeightsAndBoundedWidthsWhenResized() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextField search=new JTextField();
            JComboBox<String> country=new JComboBox<>(new String[]{"All locations","South Georgia and South Sandwich Islands"});
            country.setRenderer(new DefaultListCellRenderer(){
                @Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){
                    JLabel label=(JLabel)super.getListCellRendererComponent(list,value,index,selected,focus);
                    label.setBorder(new EmptyBorder(6,8,6,8));return label;
                }
            });
            JComboBox<String> sort=new JComboBox<>(new String[]{"Best connection","Country name"});
            JPanel filters=AppWindow.serverFilters(search,country,sort);
            for(int width:new int[]{359,439,849,1189,439}) {
                filters.setSize(width,filters.getPreferredSize().height);layout(filters);
                assertEquals(country.getHeight(),sort.getHeight(),"Different renderers must not change control heights");
                assertTrue(country.getHeight()>=32&&country.getHeight()<=44,"Dropdowns keep a comfortable height");
                assertTrue(search.getWidth()<=640,"Fullscreen must not stretch the filters indefinitely");
                Point searchAt=SwingUtilities.convertPoint(search,0,0,filters);
                Point countryAt=SwingUtilities.convertPoint(country,0,0,filters);
                Point sortAt=SwingUtilities.convertPoint(sort,0,0,filters);
                assertEquals(searchAt.x,countryAt.x);
                assertEquals(countryAt.y,sortAt.y);
                // GridLayout can leave one pixel after dividing an odd width.
                assertTrue(Math.abs(searchAt.x+search.getWidth()-sortAt.x-sort.getWidth())<=1);
                assertTrue(countryAt.x+country.getWidth()<sortAt.x,"Location and sorting remain separate");
            }
            country.setSelectedIndex(1);sort.setSelectedIndex(1);
            assertEquals("South Georgia and South Sandwich Islands",country.getSelectedItem());
            assertEquals("Country name",sort.getSelectedItem());
        });
    }
    private static void layout(Container parent) { parent.doLayout();for(Component child:parent.getComponents())if(child instanceof Container container)layout(container); }
}
