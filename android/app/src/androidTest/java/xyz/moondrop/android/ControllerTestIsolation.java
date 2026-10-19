package xyz.moondrop.android;

/** Each device test starts with its own controller and stops owned background workers. */
final class ControllerTestIsolation {
    static void reset()throws Exception{
        java.lang.reflect.Field singleton=AppController.class.getDeclaredField("instance");singleton.setAccessible(true);
        AppController current=(AppController)singleton.get(null);if(current==null)return;
        current.disconnect();
        java.lang.reflect.Field serial=AppController.class.getDeclaredField("engine");serial.setAccessible(true);((java.util.concurrent.ExecutorService)serial.get(current)).submit(()->{}).get(8,java.util.concurrent.TimeUnit.SECONDS);
        for(String name:new String[]{"network","engine"}){java.lang.reflect.Field field=AppController.class.getDeclaredField(name);field.setAccessible(true);java.util.concurrent.ExecutorService worker=(java.util.concurrent.ExecutorService)field.get(current);worker.shutdownNow();worker.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS);}
        NativeEngine.stop();singleton.set(null,null);
    }
}
