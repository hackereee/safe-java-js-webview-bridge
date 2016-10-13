package cn.pedant.SafeWebViewBridge;

import android.text.TextUtils;
import android.webkit.WebView;
import android.util.Log;

import com.google.gson.Gson;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;

public class JsCallJava {
    private final static String TAG = "JsCallJava";
    private final static String RETURN_RESULT_FORMAT = "{\"code\": %d, \"result\": %s}";
    private HashMap<String, Method> mMethodsMap;
    /*
     * 加上类型
     */
//	private HashMap<String, String> mClazzMap;
    private HashMap<String, Object> mObjectMaps;
    private String mInjectedName;
    private String mPreloadInterfaceJS;
    private Gson mGson;

    public JsCallJava(String injectedName, Object injectedObj) {
        try {
            if (TextUtils.isEmpty(injectedName)) {
                throw new Exception("injected name can not be null");
            }
            mInjectedName = injectedName;
            mMethodsMap = new HashMap<String, Method>();

            mObjectMaps = new HashMap<String, Object>();

//			mClazzMap = new HashMap<String, String>();
            // 获取自身声明的所有方法（包括public private protected）， getMethods会获得所有继承与非继承的方法
            Method[] methods = injectedObj.getClass().getDeclaredMethods();
            StringBuilder sb = new StringBuilder(
                    "javascript:(function(b){console.log(\"");
            sb.append(mInjectedName);
            sb.append(" initialization begin\");var a={queue:[],callback:function(){var d=Array.prototype.slice.call(arguments,0);var c=d.shift();var e=d.shift();this.queue[c].apply(this,d);if(!e){delete this.queue[c]}}};");
            for (Method method : methods) {
                String sign;
                if (/*
					 * method.getModifiers() == (Modifier.PUBLIC |
					 * Modifier.STATIC) ||
					 */(sign = genJavaMethodSign(method)) == null) {
                    continue;
                }
                mMethodsMap.put(sign, method);
//				mClazzMap.put(sign, injectedObj);
                mObjectMaps.put(sign, injectedObj);
                sb.append(String.format("a.%s=", method.getName()));
            }

            sb.append("function(){var f=Array.prototype.slice.call(arguments,0);if(f.length<1){throw\"");
            sb.append(mInjectedName);
            sb.append(" call error, message:miss method name\"}var e=[];for(var h=1;h<f.length;h++){var c=f[h];var j=typeof c;e[e.length]=j;if(j==\"function\"){var d=a.queue.length;a.queue[d]=c;f[h]=d}}var g=JSON.parse(prompt(JSON.stringify({method:f.shift(),types:e,args:f})));if(g.code!=200){throw\"");
            sb.append(mInjectedName);
            sb.append(" call error, code:\"+g.code+\", message:\"+g.result}return g.result};Object.getOwnPropertyNames(a).forEach(function(d){var c=a[d];if(typeof c===\"function\"&&d!==\"callback\"){a[d]=function(){return c.apply(a,[d].concat(Array.prototype.slice.call(arguments,0)))}}});b.");
            sb.append(mInjectedName);
            sb.append("=a;console.log(\"");
            sb.append(mInjectedName);
            sb.append(" initialization end\")})(window);");
            mPreloadInterfaceJS = sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "init js error:" + e.getMessage());
        }
    }

    private String genJavaMethodSign(Method method) {
        String sign = method.getName();
        Class[] argsTypes = method.getParameterTypes();
        int len = argsTypes.length;
        //如果是静态，必须含有webview参数
        int index = 0;
        if ((method.getModifiers() == (Modifier.PUBLIC | Modifier.STATIC)) &&(len < 1 || argsTypes[0] != WebView.class)) {
            Log.w(TAG, "method(" + sign
                    + ") must use webview to be first parameter in the static modifiers, will be pass");
            return null;
        }else if(method.getModifiers() == (Modifier.PUBLIC | Modifier.STATIC)){
            index = 1;
        }



        for (int k = index; k < len; k++) {
            Class cls = argsTypes[k];
            if (cls == String.class) {
                sign += "_S";
            } else if (cls == int.class || cls == long.class
                    || cls == float.class || cls == double.class) {
                sign += "_N";
            } else if (cls == boolean.class) {
                sign += "_B";
            } else if (cls == JSONObject.class) {
                sign += "_O";
            } else if (cls == JsCallback.class) {
                sign += "_F";
            } else {
                sign += "_P";
            }
        }
        return sign;
    }

    public String getPreloadInterfaceJS() {
        return mPreloadInterfaceJS;
    }

    public String call(WebView webView, String jsonStr) {
        if (!TextUtils.isEmpty(jsonStr)) {
            try {
                JSONObject callJson = new JSONObject(jsonStr);
                String methodName = callJson.getString("method");
                JSONArray argsTypes = callJson.getJSONArray("types");
                JSONArray argsVals = callJson.getJSONArray("args");
                String sign = methodName;
                int len = argsTypes.length();
                Object[] values = new Object[len ];
                int numIndex = 0;
                String currType;

//				values[0] = webView;

                for (int k = 0; k < len; k++) {
                    currType = argsTypes.optString(k);
                    if ("string".equals(currType)) {
                        sign += "_S";
                        values[k] = argsVals.isNull(k) ? null : argsVals
                                .getString(k);
                    } else if ("number".equals(currType)) {
                        sign += "_N";
                        numIndex = numIndex * 10 + k;
                    } else if ("boolean".equals(currType)) {
                        sign += "_B";
                        values[k] = argsVals.getBoolean(k);
                    } else if ("object".equals(currType)) {
                        sign += "_O";
                        values[k] = argsVals.isNull(k) ? null : argsVals
                                .getJSONObject(k);
                    } else if ("function".equals(currType)) {
                        sign += "_F";
                        values[k] = new JsCallback(webView, mInjectedName,
                                argsVals.getInt(k));
                    } else {
                        sign += "_P";
                    }
                }

                Method currMethod = mMethodsMap.get(sign);

                // 方法匹配失败
                if (currMethod == null) {
                    return getReturn(jsonStr, 500, "not found method(" + sign
                            + ") with valid parameters");
                }
                // 数字类型细分匹配
                if (numIndex > 0) {
                    Class[] methodTypes = currMethod.getParameterTypes();
                    int currIndex;
                    Class currCls;
                    while (numIndex > 0) {
                        //这里作者写错了，我改了 by hacceee
                        currIndex = numIndex - numIndex * 10;
                        currCls = methodTypes[currIndex];
                        if (currCls == int.class) {
                            values[currIndex] = argsVals.getInt(currIndex);
                        } else if (currCls == long.class) {
                            // WARN: argsJson.getLong(k + defValue) will return
                            // a bigger incorrect number
                            values[currIndex] = Long.parseLong(argsVals
                                    .getString(currIndex));
                        } else {
                            values[currIndex] = argsVals
                                    .getDouble(currIndex);
                        }
                        numIndex /= 10;
                    }
                }

                //update by hacceee
                if (currMethod.getModifiers() == (Modifier.PUBLIC | Modifier.STATIC)) {
                    Object[] staticValues = new Object[values.length + 1];
                    staticValues[0] = webView;
                    for(int s = 0; s < values.length; s++){
                        staticValues[s + 1] = values[s];
                    }
                    return getReturn(jsonStr, 200,
                            currMethod.invoke(null, values));
                } else {
                    Object object = mObjectMaps.get(sign);
                    return getReturn(jsonStr, 200, currMethod.invoke(object, values));
                }
            } catch (Exception e) {
                // 优先返回详细的错误信息
                if (e.getCause() != null) {
                    return getReturn(jsonStr, 500, "method execute error:"
                            + e.getCause().getMessage());
                }
                return getReturn(jsonStr, 500,
                        "method execute error:" + e.getMessage());
            }
        } else {
            return getReturn(jsonStr, 500, "call data empty");
        }
    }

    private String getReturn(String reqJson, int stateCode, Object result) {
        String insertRes;
        if (result == null) {
            insertRes = "null";
        } else if (result instanceof String) {
            result = ((String) result).replace("\"", "\\\"");
            insertRes = "\"" + result + "\"";
        } else if (!(result instanceof Integer) && !(result instanceof Long)
                && !(result instanceof Boolean) && !(result instanceof Float)
                && !(result instanceof Double)
                && !(result instanceof JSONObject)) { // 非数字或者非字符串的构造对象类型都要序列化后再拼接
            if (mGson == null) {
                mGson = new Gson();
            }
            insertRes = mGson.toJson(result);
        } else { // 数字直接转化
            insertRes = String.valueOf(result);
        }
        String resStr = String.format(RETURN_RESULT_FORMAT, stateCode,
                insertRes);
        Log.d(TAG, mInjectedName + " call json: " + reqJson + " result:"
                + resStr);
        return resStr;
    }
}