package com.naqqa.elasticsearch.common.settings;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class SettingsTest {

    @Test
    public void testYamlSubsetParsing() {
        String yaml = "cluster:\n  name: my-cluster\nnode:\n  attr:\n    rack: r1\nlist:\n  - a\n  - b\n# comment\nnumber: 42\n";
        Settings settings = YamlSettingsLoader.load(yaml);
        Assert.assertEquals("my-cluster", settings.get("cluster.name"));
        Assert.assertEquals("r1", settings.get("node.attr.rack"));
        Assert.assertEquals(List.of("a", "b"), settings.getAsList("list"));
        Assert.assertEquals(42, settings.getAsInt("number", -1));
    }

    @Test
    public void testPropertiesLoader() {
        Settings settings = PropertiesSettingsLoader.load("foo.bar=baz\ncount=7\n");
        Assert.assertEquals("baz", settings.get("foo.bar"));
        Assert.assertEquals(7, settings.getAsInt("count", 0));
    }

    @Test
    public void testFlattenedKeysAndTypedGetters() {
        Settings settings = Settings.builder()
            .put("time.setting", "5s")
            .put("size.setting", "10kb")
            .put("bool.setting", true)
            .build();
        Assert.assertEquals(5000L, settings.getAsTime("time.setting", null).millis());
        Assert.assertEquals(10240L, settings.getAsBytesSize("size.setting", null).getBytes());
        Assert.assertTrue(settings.getAsBoolean("bool.setting", false));
    }

    @Test
    public void testSettingDefaultsAndValidator() {
        Setting<Integer> setting = Setting.intSetting("index.number_of_shards", 1, 1, 1024, Setting.Property.INDEX_SCOPE);
        Assert.assertEquals(Integer.valueOf(1), setting.getDefault(Settings.EMPTY));
        Settings withValue = Settings.builder().put("index.number_of_shards", 5).build();
        Assert.assertEquals(Integer.valueOf(5), setting.get(withValue));
        Assert.assertThrows(IllegalArgumentException.class, () -> setting.parse("9999"));
    }

    @Test
    public void testDynamicSettingUpdateConsumer() {
        Setting<Integer> dynamicSetting = Setting.intSetting("cluster.routing.allocation.node_concurrent_recoveries", 2, Setting.Property.DYNAMIC, Setting.Property.CLUSTER_SCOPE);
        ClusterSettings clusterSettings = new ClusterSettings(Settings.EMPTY, AbstractScopedSettings.settingsSet(dynamicSetting));
        AtomicInteger observed = new AtomicInteger(-1);
        clusterSettings.addSettingsUpdateConsumer(dynamicSetting, observed::set);
        clusterSettings.applySettings(Settings.builder().put(dynamicSetting.getKey(), 9).build());
        Assert.assertEquals(9, observed.get());
    }

    @Test
    public void testFinalSettingCannotBeDynamic() {
        Assert.assertThrows(IllegalArgumentException.class, () ->
            new Setting<>("x", s -> "1", Integer::parseInt, null, Setting.Property.FINAL, Setting.Property.DYNAMIC)
        );
    }
}
