# java_init.list 按类名加载入口，并调用公开无参构造。
# 生命周期回调覆写外部 API，由 R8 保留；其余实现类可正常裁剪、优化和混淆。
-keep,allowoptimization class cc.star0.wear.xposed.cnwearoverlay.CnWearOverlayModule {
    public <init>();
}
