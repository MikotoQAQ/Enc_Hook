package JustPlus;

import android.app.Application;
import android.content.Context;
import android.net.http.SslError;
import android.webkit.SslErrorHandler;
import android.webkit.WebView;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import dalvik.system.DexFile;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.callStaticMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookConstructor;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.newInstance;
import static de.robv.android.xposed.XposedHelpers.setObjectField;

import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.HostNameResolver;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.SingleClientConnManager;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.HttpParams;


import org.json.JSONObject;
import taisuii.cn.EnConfig;
import taisuii.cn.SelfConfig;
import taisuii.cn.Util;
import taisuii.cn.taisui;

public class JustHook implements IXposedHookLoadPackage {

    //存放 全部类的 集合
    public static volatile List<Class> mClassList = new ArrayList<>();
    public static TrustManager tm = new X509TrustManager() {

        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        }

        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        }

        public X509Certificate[] getAcceptedIssuers() {
            return null;
        }
    };
    //别的进程的 context
    private Context mOtherContext;
    //别的进程的 mLoader
    private volatile ClassLoader mLoader = null;
    private List<String> classNameList = new ArrayList<String>();
    private XC_LoadPackage.LoadPackageParam lpparam;
    //OkHttp里面的 类
    private Class<?> OkHttpBuilder = null;
    private Class<?> OkHttpClient = null;
    private int flag = 0;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        String packager = loadPackageParam.packageName;
        EnConfig enConfig = new EnConfig();
        boolean filter = true;
        if (enConfig.getScope().equals("package")) filter = packager.equals(enConfig.getPackageName());

        if (enConfig.isHook() && filter) {
            if (enConfig.isJustTrustMePlus()){
                taisui.JustLog("JustTrustMePlus target: " + packager);
                this.lpparam = loadPackageParam;
                HookAttach();
            }
        }


    }

    /**
     * hook  Attach方法
     */
    private void HookAttach() {

        XposedHelpers.findAndHookMethod(Application.class, "attach",
                Context.class,
                new XC_MethodHook() {

                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        mOtherContext = (Context) param.args[0];
                        mLoader = mOtherContext.getClassLoader();

                    }
                });
        XposedHelpers.findAndHookMethod(Application.class, "onCreate",
                new XC_MethodHook() {

                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {

                        super.afterHookedMethod(param);
                        taisui.JustLog("Hook到    onCreate");
                        if (flag == 0) {
                            //在 oncteate的 执行完毕 这个时候 壳的 类以及初始化 完毕了
                            getAllClassName();
                            GetJustMePlushHook();
                            //防止 加壳的 app 做了混淆 在 onCreate 执行结束 在进行 挂钩
                            processOkHttp(mLoader);
                            processHttpClientAndroidLib(mLoader);
                            processXutils(mLoader);
                            HookOkHttpClient();
                            flag = 1;
                        }

                    }

                });
    }

    /**
     * 主要Hook这几个方法
     * <p>
     * //证书检测
     * builder.sslSocketFactory()
     * //域名验证
     * .hostnameVerifier()
     * //证书锁定
     * .certificatePinner()
     */
    private void HookOkHttpClient() {
        initAllClass();
        getClientClass();
        getBuilder();

        //方法 1   证书检测   2个 参数类型
        if (OkHttpBuilder != null) {
            Class SSLSocketFactoryClass = getClass("javax.net.ssl.SSLSocketFactory");
            Class X509TrustManagerClass = getClass("javax.net.ssl.X509TrustManager");
            //先拿到 参数类型的 类
            Method SslSocketFactoryMethod = getSslSocketFactoryMethod(SSLSocketFactoryClass, X509TrustManagerClass);
            if (SslSocketFactoryMethod != null) {
                taisui.JustLog("拿到  SslSocketFactoryMethod " + SslSocketFactoryMethod.getName());
                //需要先拿到方法名字
                XposedHelpers.findAndHookMethod(OkHttpBuilder, SslSocketFactoryMethod.getName(),
                        SSLSocketFactoryClass,
                        X509TrustManagerClass,
                        new XC_MethodHook() {

                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                super.beforeHookedMethod(param);
                                taisui.JustLog("Hook到 sslSocketFactory 2个参数类型 ");
                                param.args[0] = getEmptySSLFactory();
                                param.args[1] = new MyX509TrustManager();
                                taisui.JustLog(" sslSocketFactory 2个参数类型  替换成功 ");
                            }
                        });
            } else {
                taisui.JustLog("没有拿到  SslSocketFactoryMethod ");
            }
            Method sslSocketFactoryMethodOneType = getSslSocketFactoryMethodOneType(SSLSocketFactoryClass);

            //方法 2  证书检测   1个 参数类型

            if (sslSocketFactoryMethodOneType != null) {
                taisui.JustLog("拿到  sslSocketFactoryMethodOneType   " + sslSocketFactoryMethodOneType.getName());
                //需要先拿到方法名字
                XposedHelpers.findAndHookMethod(OkHttpBuilder, sslSocketFactoryMethodOneType.getName(),
                        SSLSocketFactoryClass,
                        new XC_MethodHook() {

                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                super.beforeHookedMethod(param);
                                taisui.JustLog("Hook到 sslSocketFactory 1个参数类型 ");
                                param.args[0] = getEmptySSLFactory();
                                taisui.JustLog(" sslSocketFactory 1个参数类型  替换成功 ");
                            }
                        });
            } else {
                taisui.JustLog("没有拿到  sslSocketFactoryMethodOneType ");
            }


            //域名 验证
            Class HostnameVerifierClass = getClass("javax.net.ssl.HostnameVerifier");
            if (HostnameVerifierClass != null) {
                taisui.JustLog("拿到  HostnameVerifier 类  ");
            }
            Method hostnameVerifierMethod = gethostnameVerifierMethod(HostnameVerifierClass);
            if (hostnameVerifierMethod != null) {
                taisui.JustLog(" 拿到 hostnameVerifierMethod  " + hostnameVerifierMethod.getName());
                //需要先拿到方法名字
                XposedHelpers.findAndHookMethod(OkHttpBuilder, hostnameVerifierMethod.getName(),
                        HostnameVerifierClass,
                        new XC_MethodHook() {

                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                super.beforeHookedMethod(param);
                                taisui.JustLog("Hook到 hostnameVerifier  ");
                                param.args[0] = new MyHostnameVerifier();
                                taisui.JustLog(" hostnameVerifier 替换成功 ");
                            }
                        });
            } else {
                taisui.JustLog("没有拿到  hostnameVerifierMethod ");
            }


            //证书 锁定
            Class CertificatePinnerClass = getCertificatePinnerClass();
            if (CertificatePinnerClass != null) {
                taisui.JustLog("得到  CertificatePinner  名字是  " + CertificatePinnerClass.getName());


                Method CertificatePinnerCheckMethod = getCertificatePinnerCheckMethod(CertificatePinnerClass);
                Method okHttpCertificatePinnerCheckMethod = getOkHttpCertificatePinnerCheckMethod(CertificatePinnerClass);

                if (CertificatePinnerCheckMethod != null) {
                    taisui.JustLog("得到  CertificatePinnerCheckMethod  名字是  " + CertificatePinnerCheckMethod.getName());


                    findAndHookMethod(CertificatePinnerClass,
                            CertificatePinnerCheckMethod.getName(),
                            String.class,
                            List.class,
                            new XC_MethodReplacement() {

                                protected Object replaceHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                                    return null;
                                }
                            });
                } else {
                    taisui.JustLog("没有拿到  CertificatePinnerCheckMethod ");
                }
                if (okHttpCertificatePinnerCheckMethod != null) {
                    taisui.JustLog("拿到  okHttpCertificatePinnerCheckMethod    名字是 " + okHttpCertificatePinnerCheckMethod.getName());
                    findAndHookMethod(OkHttpBuilder,
                            okHttpCertificatePinnerCheckMethod.getName(),
                            CertificatePinnerClass,
                            new XC_MethodReplacement() {
                                protected Object replaceHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                                    return null;
                                }
                            });
                } else {
                    taisui.JustLog("没有拿到  okHttpCertificatePinnerCheckMethod ");
                }
            } else {
                taisui.JustLog("没有拿到  CertificatePinnerClass ");
            }


            Class OkHostnameVerifierClass = OkHostnameVerifierClass();
            if (OkHostnameVerifierClass != null) {
                try {

                    taisui.JustLog("得到  OkHostnameVerifierClass  名字是  " + OkHostnameVerifierClass.getName());
                    //这个 有几率为 null
                    Class<?> HostnameVerifier = Class.forName("javax.net.ssl.HostnameVerifier", true, mLoader);
                    if (HostnameVerifier == null) {
                        taisui.JustLog("没有 得到 HostnameVerifier  ");
                    }
                    Method hostnameVerifierVerifyMethod = getHostnameVerifierVerifyMethod(HostnameVerifier);
                    if (hostnameVerifierVerifyMethod != null) {
                        taisui.JustLog("得到  hostnameVerifierVerifyMethod  名字是  " + hostnameVerifierVerifyMethod.getName());

                        findAndHookMethod(OkHostnameVerifierClass,
                                hostnameVerifierVerifyMethod.getName(),
                                String.class,
                                SSLSession.class,
                                new XC_MethodReplacement() {

                                    protected Object replaceHookedMethod(MethodHookParam methodHookParam) throws Throwable {

                                        taisui.JustLog("Hook到   hostnameVerifierVerifyMethod  方法1 " + hostnameVerifierVerifyMethod.getName());
                                        return true;
                                    }
                                });
                        findAndHookMethod(OkHostnameVerifierClass,
                                hostnameVerifierVerifyMethod.getName(),
                                String.class,
                                java.security.cert.X509Certificate.class,
                                new XC_MethodReplacement() {

                                    protected Object replaceHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                                        taisui.JustLog("Hook到   hostnameVerifierVerifyMethod  方法2" + hostnameVerifierVerifyMethod.getName());
                                        return true;
                                    }
                                });
                    } else {
                        taisui.JustLog("没有 得到 hostnameVerifierVerifyMethod  ");
                    }
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                }
            } else {
                taisui.JustLog("没有找到 OkHostnameVerifierClass");
            }


        }


    }

    private Method getHostnameVerifierVerifyMethod(Class<?> hostnameVerifier) {
        Method[] declaredMethods = null;
        try {
            declaredMethods = hostnameVerifier.getDeclaredMethods();
        } catch (Exception e) {
            taisui.JustLog("getHostnameVerifierVerifyMethod   hostnameVerifier ==null");
            e.printStackTrace();
        }
        for (Method method : declaredMethods) {
            if (method.getParameterTypes().length == 2) {
                if (method.getParameterTypes()[0].getName().equals(String.class.getName()) &&
                        method.getParameterTypes()[1].getName().equals(SSLSession.class.getName())) {
                    return method;
                }
            }
        }
        return null;
    }

    private Method getCertificatePinnerCheckMethod(Class certificatePinnerClass) {
        Method[] declaredMethods = certificatePinnerClass.getDeclaredMethods();
        for (Method method : declaredMethods) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length == 2) {
                if (parameterTypes[0].getName().equals(String.class.getName()) && parameterTypes[1].getName().equals(List.class.getName())) {
                    return method;
                }
            }
        }
        return null;
    }

    private Method getOkHttpCertificatePinnerCheckMethod(Class certificatePinnerClass) {
        Method[] declaredMethods = OkHttpBuilder.getDeclaredMethods();
        for (Method method : declaredMethods) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length == 1) {
                if (parameterTypes[0].getName().equals(certificatePinnerClass.getName())) {
                    return method;
                }
            }
        }
        return null;
    }

    private Class OkHostnameVerifierClass() {

//                public final class OkHostnameVerifier implements HostnameVerifier {
//                public static final OkHostnameVerifier INSTANCE = new OkHostnameVerifier();

//                private static final int ALT_DNS_NAME = 2;
//                private static final int ALT_IPA_NAME = 7;

        try {
            Class<?> HostnameVerifier = Class.forName("javax.net.ssl.HostnameVerifier", true, mLoader);

            if (HostnameVerifier == null) {
                taisui.JustLog("OkHostnameVerifierClass  HostnameVerifier  没有找到    ");
            } else {
                taisui.JustLog("找到了 HostnameVerifierClass     ");
            }
            for (Class mClass : mClassList) {
                int privateCount = 0;
                if (mClass.getInterfaces().length == 1 && mClass.getInterfaces()[0].getName().equals("javax.net.ssl.HostnameVerifier")) {
                    taisui.JustLog("找到接口类型是    HostnameVerifier      ");

                    //接口类型 是 HostnameVerifier 并且是 final类型
                    if (Modifier.isFinal(mClass.getModifiers())) {
                        Field[] declaredFields = mClass.getDeclaredFields();
                        taisui.JustLog("发现这个类是 final  开始 遍历 字段个数       field个数    " + declaredFields.length
                                + "  该类的名字是    " + mClass.getName());


                        // 三个变量都是 final和 static类型
                        if (declaredFields.length == 3) {
                            taisui.JustLog("发现这个类是 final   字段个数    为 三个    " + mClass.getName());

                            for (Field field : declaredFields) {
                                if (Modifier.isPrivate(field.getModifiers()) && Modifier.isStatic(field.getModifiers())) {
                                    privateCount++;
                                }
                            }
                            if (privateCount == 2) {
                                return mClass;
                            }
                            taisui.JustLog("Field 静态 并且 私有的个数 的个数 是       " + privateCount);
                        }
                    }
                }
            }
        } catch (ClassNotFoundException e) {
            taisui.JustLog("OkHostnameVerifierClass  ClassNotFoundException  " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    private Class getCertificatePinnerClass() {
//            public final class CertificatePinner {
//            public static final CertificatePinner DEFAULT = (new CertificatePinner.Builder()).build();
//            private final Set<CertificatePinner.Pin> pins;
//            @Nullable
//            private final CertificateChainCleaner certificateChainCleaner;

        //本身是 final类型  三个变量 都是final类型
        //两个是 private 一个是 pubulic
        // 有一个遍历的类型是 set
        for (Class mClass : mClassList) {
            int privateCount = 0;
            int publicCount = 0;
            int SetTypeCount = 0;

            Field[] declaredFields = mClass.getDeclaredFields();
            //长度 是 3 本身是 final类型
            if (declaredFields.length == 3 && Modifier.isFinal(mClass.getModifiers())) {
                for (Field field : declaredFields) {
                    //私有 并且是 final类型
                    if (Modifier.isFinal(field.getModifiers()) && Modifier.isPrivate(field.getModifiers())) {
                        privateCount++;
                        if (field.getType().getName().equals(Set.class.getName())) {
                            SetTypeCount++;
                        }
                    }
                    if (Modifier.isFinal(field.getModifiers()) && Modifier.isPublic(field.getModifiers())) {
                        publicCount++;
                    }
                }
                if (publicCount == 1 && SetTypeCount == 1 && privateCount == 2) {
                    return mClass;
                }
            }
        }
        return null;
    }

    private Method gethostnameVerifierMethod(Class HostnameVerifierClass) {
        Method[] declaredMethods = OkHttpBuilder.getDeclaredMethods();
        for (int i = 0; i < declaredMethods.length; i++) {
            declaredMethods[i].setAccessible(true);
            Class<?>[] parameterTypes = declaredMethods[i].getParameterTypes();
            if (parameterTypes.length == 1) {
                if (parameterTypes[0].getName().equals(HostnameVerifierClass.getName())) {
                    return declaredMethods[i];
                }
            }
        }
        return null;
    }

    private Method getSslSocketFactoryMethod(Class sslSocketFactoryClass, Class x509TrustManagerClass) {
        Method[] declaredMethods = OkHttpBuilder.getDeclaredMethods();
        for (int i = 0; i < declaredMethods.length; i++) {
            declaredMethods[i].setAccessible(true);
            Class<?>[] parameterTypes = declaredMethods[i].getParameterTypes();
            if (parameterTypes.length == 2) {
                if (parameterTypes[0].getName().equals(sslSocketFactoryClass.getName()) &&
                        parameterTypes[1].getName().equals(x509TrustManagerClass.getName())) {
                    return declaredMethods[i];
                }
            }
        }
        taisui.JustLog("没有找到  SslSocketFactoryMethod  ");
        return null;
    }

    private Method getSslSocketFactoryMethodOneType(Class sslSocketFactoryClass) {
        Method[] declaredMethods = OkHttpBuilder.getDeclaredMethods();
        for (int i = 0; i < declaredMethods.length; i++) {
            declaredMethods[i].setAccessible(true);
            Class<?>[] parameterTypes = declaredMethods[i].getParameterTypes();
            if (parameterTypes.length == 1) {
                if (parameterTypes[0].getName().equals(sslSocketFactoryClass.getName())) {
                    return declaredMethods[i];
                }
            }
        }
        return null;
    }

    private Class getClass(String path) {
        if (path != null && !path.equals("")) {
            Class<?> aClass = null;
            try {
                aClass = Class.forName(path, true, mLoader);
                if (aClass == null) {
                    aClass = Class.forName(path);
                }
                if (aClass != null) {
                    return aClass;
                }
            } catch (ClassNotFoundException e) {
                taisui.JustLog("getclass 反射类 失败   " + e.getMessage());
                e.printStackTrace();
            }
        }
        return null;
    }

    /**
     * 混淆 以后 获取  集合并添加 拦截器的方法
     */
    private void getBuilder() {
        if (OkHttpClient == null) {
            taisui.JustLog("混淆以后  OkHttpClient==Null ");
            getClientClass();
            return;
        } else {
            //开始查找 build
            for (Class builder : mClassList) {
                if (isBuilder(builder)) {
                    OkHttpBuilder = builder;
                }
            }
        }
    }

    private boolean isBuilder(Class ccc) {

        int ListTypeCount = 0;
        int FinalTypeCount = 0;
        Field[] fields = ccc.getDeclaredFields();
        List<Field> List = new ArrayList<>();
        for (Field field : fields) {
            String type = field.getType().getName();
            //四个 集合
            if (type.contains("java.util.List")) {
                ListTypeCount++;
            }
            //2 个 为 final类型
            if (type.contains("java.util.List") && Modifier.isFinal(field.getModifiers())) {
                List.add(field);
                FinalTypeCount++;
            }
        }
        //四个 List 两个 2 final  并且 包含父类名字
        if (ListTypeCount == 4 && FinalTypeCount == 2 && ccc.getName().contains(OkHttpClient.getName())) {
            taisui.JustLog(" 找到 Builer  " + ccc.getName());
            return true;
        }
        return false;
    }

    /**
     * 获取 ClientCLass的方法
     */
    private void getClientClass() {
        if (mClassList.size() == 0) {
            taisui.JustLog("全部的 集合 mClassList  的个数 为 0  ");
            return;
        }
        for (Class mClient : mClassList) {
            //判断 集合 个数 先拿到 四个集合 可以 拿到 Client
            if (isClient(mClient)) {
                OkHttpClient = mClient;
                taisui.JustLog("找到了 外层  开始 找内层 ");
                return;
            }
        }
    }

    private boolean isClient(Class<?> mClass) {
        int typeCount = 0;
        //getDeclaredFields 是个 获取 全部的
        Field[] fields = mClass.getDeclaredFields();

        for (Field field : fields) {
            field.setAccessible(true);
            String type = field.getType().getName();

            //四个 集合 四个final 特征
            if (type.contains("java.util.List") && Modifier.isFinal(field.getModifiers())) {
                //taisui.JustLog(" 复合 规则 该 Field是      " + field.getName() + " ");
                typeCount++;
            }
        }

        if (typeCount == 6) {
            taisui.JustLog(" OkHttpClient该类的名字是  " + mClass.getName());
            return true;
        }
        return false;
    }

    /**
     * 初始化 需要的 class的 方法
     */
    private void initAllClass() {
        try {
            for (String path : classNameList) {
                //首先进行初始化
                mClassList.add(Class.forName(path, true, mLoader));
            }
            taisui.JustLog("初始化  跟 OkHttp类的个数 " + mClassList.size());
        } catch (ClassNotFoundException e) {
            taisui.JustLog("initAllClass  报错 路径实例化类  " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void processXutils(ClassLoader classLoader) {
        try {
            classLoader.loadClass("org.xutils.http.RequestParams");
            findAndHookMethod("org.xutils.http.RequestParams", classLoader,
                    "setSslSocketFactory", javax.net.ssl.SSLSocketFactory.class, new XC_MethodHook() {

                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            super.beforeHookedMethod(param);
                            param.args[0] = getEmptySSLFactory();
                        }
                    });
            findAndHookMethod("org.xutils.http.RequestParams", classLoader, "setHostnameVerifier", HostnameVerifier.class, new XC_MethodHook() {

                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    param.args[0] = new ImSureItsLegitHostnameVerifier();
                }
            });
        } catch (Exception e) {
        }
    }

    public static javax.net.ssl.SSLSocketFactory getEmptySSLFactory() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new ImSureItsLegitTrustManager()}, null);
            return sslContext.getSocketFactory();
        } catch (NoSuchAlgorithmException e) {
            return null;
        } catch (KeyManagementException e) {
            return null;
        }
    }

    private void processHttpClientAndroidLib(ClassLoader classLoader) {
        /* httpclientandroidlib Hooks */
        /* public final void verify(String host, String[] cns, String[] subjectAlts, boolean strictWithSubDomains) throws SSLException */

        try {
            classLoader.loadClass("ch.boye.httpclientandroidlib.conn.ssl.AbstractVerifier");
            findAndHookMethod("ch.boye.httpclientandroidlib.conn.ssl.AbstractVerifier", classLoader, "verify",
                    String.class, String[].class, String[].class, boolean.class,
                    new XC_MethodReplacement() {

                        protected Object replaceHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                            return null;
                        }
                    });
        } catch (ClassNotFoundException e) {
            // pass
        }
    }

    private void processOkHttp(ClassLoader classLoader) {
        /* hooking OKHTTP by SQUAREUP */
        /* com/squareup/okhttp/CertificatePinner.java available online @ https://github.com/square/okhttp/blob/master/okhttp/src/main/java/com/squareup/okhttp/CertificatePinner.java */
        /* public void check(String hostname, List<Certificate> peerCertificates) throws SSLPeerUnverifiedException{}*/
        /* Either returns true or a exception so blanket return true */
        /* Tested against version 2.5 */

        try {
            classLoader.loadClass("com.squareup.okhttp.CertificatePinner");
            findAndHookMethod("com.squareup.okhttp.CertificatePinner",
                    classLoader,
                    "check",
                    String.class,
                    List.class,
                    new XC_MethodReplacement() {

                        protected Object replaceHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                            return true;
                        }
                    });
        } catch (ClassNotFoundException e) {
            // pass
        }

        //https://github.com/square/okhttp/blob/parent-3.0.1/okhttp/src/main/java/okhttp3/CertificatePinner.java#L144

        try {
            classLoader.loadClass("okhttp3.CertificatePinner");

            findAndHookMethod("okhttp3.CertificatePinner",
                    classLoader,
                    "check",
                    String.class,
                    List.class,
                    new XC_MethodReplacement() {

                        protected Object replaceHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                            return null;
                        }
                    });
        } catch (ClassNotFoundException e) {
            // pass
        }

        //https://github.com/square/okhttp/blob/parent-3.0.1/okhttp/src/main/java/okhttp3/internal/tls/OkHostnameVerifier.java
        try {
            classLoader.loadClass("okhttp3.internal.tls.OkHostnameVerifier");
            findAndHookMethod("okhttp3.internal.tls.OkHostnameVerifier",
                    classLoader,
                    "verify",
                    String.class,
                    javax.net.ssl.SSLSession.class,
                    new XC_MethodReplacement() {

                        protected Object replaceHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                            return true;
                        }
                    });
        } catch (ClassNotFoundException e) {
            // pass
        }

        //https://github.com/square/okhttp/blob/parent-3.0.1/okhttp/src/main/java/okhttp3/internal/tls/OkHostnameVerifier.java
        try {
            classLoader.loadClass("okhttp3.internal.tls.OkHostnameVerifier");
            findAndHookMethod("okhttp3.internal.tls.OkHostnameVerifier",
                    classLoader,
                    "verify",
                    String.class,
                    java.security.cert.X509Certificate.class,
                    new XC_MethodReplacement() {

                        protected Object replaceHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                            return true;
                        }
                    });
        } catch (ClassNotFoundException e) {
            // pass
        }
    }

    private void GetJustMePlushHook() {
        /* Apache Hooks */
        /* external/apache-http/src/org/apache/http/impl/client/DefaultHttpClient.java */
        /* public DefaultHttpClient() */
        findAndHookConstructor(DefaultHttpClient.class, new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {

                setObjectField(param.thisObject, "defaultParams", null);
                setObjectField(param.thisObject, "connManager", getSCCM());
            }
        });



        /* external/apache-http/src/org/apache/http/impl/client/DefaultHttpClient.java */
        /* public DefaultHttpClient(HttpParams params) */
        findAndHookConstructor(DefaultHttpClient.class, HttpParams.class, new XC_MethodHook() {

            protected void afterHookedMethod(MethodHookParam param) throws Throwable {

                setObjectField(param.thisObject, "defaultParams", param.args[0]);
                setObjectField(param.thisObject, "connManager", getSCCM());
            }
        });

        /* external/apache-http/src/org/apache/http/impl/client/DefaultHttpClient.java */
        /* public DefaultHttpClient(ClientConnectionManager conman, HttpParams params) */
        findAndHookConstructor(DefaultHttpClient.class, ClientConnectionManager.class, HttpParams.class, new XC_MethodHook() {

            protected void afterHookedMethod(MethodHookParam param) throws Throwable {

                HttpParams params = (HttpParams) param.args[1];

                setObjectField(param.thisObject, "defaultParams", params);
                setObjectField(param.thisObject, "connManager", getCCM(param.args[0], params));
            }
        });

        /* external/apache-http/src/org/apache/http/conn/ssl/SSLSocketFactory.java */
        /* public SSLSocketFactory( ... ) */
        findAndHookConstructor(SSLSocketFactory.class, String.class, KeyStore.class, String.class, KeyStore.class,
                SecureRandom.class, HostNameResolver.class, new XC_MethodHook() {

                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {

                        String algorithm = (String) param.args[0];
                        KeyStore keystore = (KeyStore) param.args[1];
                        String keystorePassword = (String) param.args[2];
                        SecureRandom random = (SecureRandom) param.args[4];

                        KeyManager[] keymanagers = null;
                        TrustManager[] trustmanagers = null;

                        if (keystore != null) {
                            keymanagers = (KeyManager[]) callStaticMethod(SSLSocketFactory.class, "createKeyManagers", keystore, keystorePassword);
                        }

                        trustmanagers = new TrustManager[]{new ImSureItsLegitTrustManager()};

                        setObjectField(param.thisObject, "sslcontext", SSLContext.getInstance(algorithm));
                        callMethod(getObjectField(param.thisObject, "sslcontext"), "init", keymanagers, trustmanagers, random);
                        setObjectField(param.thisObject, "socketfactory",
                                callMethod(getObjectField(param.thisObject, "sslcontext"), "getSocketFactory"));
                    }

                });


        /* external/apache-http/src/org/apache/http/conn/ssl/SSLSocketFactory.java */
        /* public static SSLSocketFactory getSocketFactory() */
        findAndHookMethod("org.apache.http.conn.ssl.SSLSocketFactory", lpparam.classLoader, "getSocketFactory", new XC_MethodReplacement() {

            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                return newInstance(SSLSocketFactory.class);
            }
        });

        /* external/apache-http/src/org/apache/http/conn/ssl/SSLSocketFactory.java */
        /* public boolean isSecure(Socket) */
        findAndHookMethod("org.apache.http.conn.ssl.SSLSocketFactory", lpparam.classLoader, "isSecure", Socket.class, new XC_MethodReplacement() {

            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                return true;
            }
        });

        /* JSSE Hooks */
        /* libcore/luni/src/main/java/javax/net/ssl/TrustManagerFactory.java */
        /* public final TrustManager[] getTrustManager() */
        findAndHookMethod("javax.net.ssl.TrustManagerFactory", lpparam.classLoader, "getTrustManagers", new XC_MethodHook() {

            protected void afterHookedMethod(MethodHookParam param) throws Throwable {

                if (hasTrustManagerImpl()) {
                    Class<?> cls = findClass("com.android.org.conscrypt.TrustManagerImpl", lpparam.classLoader);

                    TrustManager[] managers = (TrustManager[]) param.getResult();
                    if (managers.length > 0 && cls.isInstance(managers[0]))
                        return;
                }

                param.setResult(new TrustManager[]{new ImSureItsLegitTrustManager()});
            }
        });

        /* libcore/luni/src/main/java/javax/net/ssl/HttpsURLConnection.java */
        /* public void setDefaultHostnameVerifier(HostnameVerifier) */
        findAndHookMethod("javax.net.ssl.HttpsURLConnection", lpparam.classLoader, "setDefaultHostnameVerifier",
                HostnameVerifier.class, new XC_MethodReplacement() {

                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return null;
                    }
                });

        /* libcore/luni/src/main/java/javax/net/ssl/HttpsURLConnection.java */
        /* public void setSSLSocketFactory(SSLSocketFactory) */
        findAndHookMethod("javax.net.ssl.HttpsURLConnection", lpparam.classLoader, "setSSLSocketFactory", javax.net.ssl.SSLSocketFactory.class,
                new XC_MethodReplacement() {

                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return null;
                    }
                });

        /* libcore/luni/src/main/java/javax/net/ssl/HttpsURLConnection.java */
        /* public void setHostnameVerifier(HostNameVerifier) */
        findAndHookMethod("javax.net.ssl.HttpsURLConnection", lpparam.classLoader, "setHostnameVerifier", HostnameVerifier.class,
                new XC_MethodReplacement() {

                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return null;
                    }
                });


        /* WebView Hooks */
        /* frameworks/base/core/java/android/webkit/WebViewClient.java */
        /* public void onReceivedSslError(Webview, SslErrorHandler, SslError) */

        findAndHookMethod("android.webkit.WebViewClient", lpparam.classLoader, "onReceivedSslError",
                WebView.class, SslErrorHandler.class, SslError.class, new XC_MethodReplacement() {

                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        ((android.webkit.SslErrorHandler) param.args[1]).proceed();
                        return null;
                    }
                });

        /* frameworks/base/core/java/android/webkit/WebViewClient.java */
        /* public void onReceivedError(WebView, int, String, String) */

        findAndHookMethod("android.webkit.WebViewClient", lpparam.classLoader, "onReceivedError",
                WebView.class, int.class, String.class, String.class, new XC_MethodReplacement() {

                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return null;
                    }
                });

        //SSLContext.init >> (null,ImSureItsLegitTrustManager,null)
        findAndHookMethod("javax.net.ssl.SSLContext", lpparam.classLoader, "init", KeyManager[].class, TrustManager[].class, SecureRandom.class, new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                param.args[0] = null;
                param.args[1] = new TrustManager[]{new ImSureItsLegitTrustManager()};
                param.args[2] = null;

            }
        });

    }

    /* Helpers */
    // Check for TrustManagerImpl class
    public static boolean hasTrustManagerImpl() {

        try {
            Class.forName("com.android.org.conscrypt.TrustManagerImpl");
        } catch (ClassNotFoundException e) {
            return false;
        }
        return true;
    }

    private void getAllClassName() {
        taisui.JustLog("开始 获取全部的类名  ");

        classNameList.clear();

        try {
            //系统的 classloader是 Pathclassloader需要 拿到他的 父类 BaseClassloader才有 pathList
            Field pathListField = Objects.requireNonNull(mLoader.getClass().getSuperclass()).getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object dexPathList = pathListField.get(mLoader);
            Field dexElementsField = dexPathList.getClass().getDeclaredField("dexElements");
            dexElementsField.setAccessible(true);
            Object[] dexElements = (Object[]) dexElementsField.get(dexPathList);
            for (int i = 0; i < dexElements.length; i++) {
                Field dexFileField = dexElements[i].getClass().getDeclaredField("dexFile");
                dexFileField.setAccessible(true);
                DexFile dexFile = (DexFile) dexFileField.get(dexElements[i]);
                getDexFileClassName(dexFile);
            }
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        return;
    }

    private void getDexFileClassName(DexFile dexFile) {
        //获取df中的元素  这里包含了所有可执行的类名 该类名包含了包名+类名的方式
        Enumeration<String> enumeration = dexFile.entries();
        while (enumeration.hasMoreElements()) {//遍历
            String className = enumeration.nextElement();
//            taisui.JustLog(className);
            if (className.contains("okhttp3")) {//在当前所有可执行的类里面查找包含有该包名的所有类
                classNameList.add(className);
            }
        }
    }

    //This function creates a ThreadSafeClientConnManager that trusts everyone!
    public static ClientConnectionManager getTSCCM(HttpParams params) {

        KeyStore trustStore;
        try {

            trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);

            SSLSocketFactory sf = new TrustAllSSLSocketFactory(trustStore);
            sf.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);

            SchemeRegistry registry = new SchemeRegistry();
            registry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
            registry.register(new Scheme("https", sf, 443));

            ClientConnectionManager ccm = new ThreadSafeClientConnManager(params, registry);

            return ccm;

        } catch (Exception e) {
            return null;
        }
    }

    //This function determines what object we are dealing with.
    public static ClientConnectionManager getCCM(Object o, HttpParams params) {

        String className = o.getClass().getSimpleName();

        if (className.equals("SingleClientConnManager")) {
            return getSCCM();
        } else if (className.equals("ThreadSafeClientConnManager")) {
            return getTSCCM(params);
        }

        return null;
    }

    //Create a SingleClientConnManager that trusts everyone!
    public static ClientConnectionManager getSCCM() {

        KeyStore trustStore;
        try {

            trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);

            SSLSocketFactory sf = new TrustAllSSLSocketFactory(trustStore);
            sf.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);

            SchemeRegistry registry = new SchemeRegistry();
            registry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
            registry.register(new Scheme("https", sf, 443));

            ClientConnectionManager ccm = new SingleClientConnManager(null, registry);

            return ccm;

        } catch (Exception e) {
            return null;
        }
    }

    class MySSLSocketFactory extends javax.net.ssl.SSLSocketFactory {


        public String[] getDefaultCipherSuites() {
            return new String[0];
        }


        public String[] getSupportedCipherSuites() {
            return new String[0];
        }


        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return null;
        }


        public Socket createSocket(String host, int port) throws IOException {
            return null;
        }


        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return null;
        }


        public Socket createSocket(InetAddress host, int port) throws IOException {
            return null;
        }


        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return null;
        }
    }

    public static class MyX509TrustManager implements X509TrustManager {


        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {

        }


        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {

        }


        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    public static class MyHostnameVerifier implements HostnameVerifier {


        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    }

    public static class ImSureItsLegitHostnameVerifier implements HostnameVerifier {


        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    }

    public static class ImSureItsLegitTrustManager implements X509TrustManager {

        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        }


        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        }


        public List<X509Certificate> checkServerTrusted(X509Certificate[] chain, String authType, String host) throws CertificateException {
            ArrayList<X509Certificate> list = new ArrayList<X509Certificate>();
            return list;
        }


        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /* This class creates a SSLSocket that trusts everyone. */
    public static class TrustAllSSLSocketFactory extends SSLSocketFactory {

        SSLContext sslContext = SSLContext.getInstance("TLS");

        public TrustAllSSLSocketFactory(KeyStore truststore) throws
                NoSuchAlgorithmException, KeyManagementException, KeyStoreException, UnrecoverableKeyException {
            super(truststore);

            sslContext.init(null, new TrustManager[]{tm}, null);
        }


        public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
            return sslContext.getSocketFactory().createSocket(socket, host, port, autoClose);
        }


        public Socket createSocket() throws IOException {
            return sslContext.getSocketFactory().createSocket();
        }
    }
}
