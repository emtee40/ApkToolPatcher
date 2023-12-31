package apk.tool.patcher.net;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Observer;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

import javax.net.ssl.SSLContext;

import apk.tool.patcher.App;
import apk.tool.patcher.net.api.NetworkApi;
import apk.tool.patcher.net.api.IWebClient;
import apk.tool.patcher.net.api.NetworkRequest;
import apk.tool.patcher.net.api.NetworkResponse;
import apk.tool.patcher.net.common.SimpleObservable;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class Client implements IWebClient {
    private final static String LOG_TAG = Client.class.getSimpleName();
    public static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 9.0; Pixel 3XL Build/_BuildID_) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/72.0.0.0 Mobile Safari/537.36";
    private static Client INSTANCE = null;
    private Map<String, Cookie> clientCookies = new HashMap<>();
    private SimpleObservable networkObservables = new SimpleObservable();
    private Handler observerHandler = new Handler(Looper.getMainLooper());
    private List<String> privateHeaders = new ArrayList<>(Arrays.asList("pass_hash", "session_id", "auth_key", "password"));
    private final Cookie mobileCookie = Cookie.parse(HttpUrl.parse(NetworkApi.CASINO), "ngx_mb=1;");

    //Контекст нужен, для чтения настроек
    //Не необходимо, но вдруг случится шо у App не будет контекста
    public Client(Context context) {
        if (context == null) {
            context = App.get();
        }
        NetworkApi.setWebClient(this);
    }

    public String getAuthKey() {
        return App.get().getPreferences().getString("auth_key", "0");
    }

    public static Client get() {
        return get(App.get());
    }

    public static Client get(Context context) {
        if (INSTANCE == null) INSTANCE = new Client(context);
        return INSTANCE;
    }

    private Cookie parseCookie(String cookieFields) {
        /*Хранение: Url|:|Cookie*/
        String[] fields = cookieFields.split("\\|:\\|");
        return Cookie.parse(HttpUrl.parse(fields[0]), fields[1]);
    }

    private String cookieToPref(String url, Cookie cookie) {
        return url.concat("|:|").concat(cookie.toString());
    }

    public Map<String, Cookie> getClientCookies() {
        return clientCookies;
    }

    private final CookieJar cookieJar = new CookieJar() {

        @Override
        public void saveFromResponse(@NonNull HttpUrl url, @NonNull List<Cookie> cookies) {
            SharedPreferences.Editor editor = App.get().getPreferences().edit();
            /*for (Cookie cookie : cookies) {
                Log.e("SUKA", "save COOK " + cookie.name() + " : " + cookie.value());
            }*/
            for (Cookie cookie : cookies) {
                if (cookie.value().equals("deleted")) {
                    editor.remove("cookie_".concat(cookie.name()));
                    clientCookies.remove(cookie.name());
                } else {
                    editor.putString("cookie_".concat(cookie.name()), cookieToPref(url.toString(), cookie));
                    if (cookie.name().equals("member_id")) {
                        editor.putString("member_id", cookie.value());
                        ClientHelper.setUserId(cookie.value());
                    }
                    if (!clientCookies.containsKey(cookie.name())) {
                        clientCookies.remove(cookie.name());
                    }
                    clientCookies.put(cookie.name(), cookie);
                }
            }
            editor.apply();
        }

        @Override
        public List<Cookie> loadForRequest(@NonNull HttpUrl url) {
            boolean external = !url.host().toLowerCase().contains("4pda");
            if (!external) {
                clientCookies.put("ngx_mb", mobileCookie);
            }

            List<Cookie> cookies = new ArrayList<>(clientCookies.values());
            if (external) {
                for (String privateName : privateHeaders) {
                    for (int i = 0; i < cookies.size(); i++) {
                        if (cookies.get(i).name().equals(privateName)) {
                            cookies.remove(i);
                            break;
                        }
                    }
                }
            }
            /*for (Cookie cookie : cookies) {
                Log.e("SUKA", "load COOK " + cookie.name() + " : " + cookie.value());
            }*/
            return cookies;
        }
    };

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .sslSocketFactory(getNewSslContext().getSocketFactory())
            .cookieJar(cookieJar)
            .build();

    private final OkHttpClient webSocketClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .sslSocketFactory(getNewSslContext().getSocketFactory())
            .retryOnConnectionFailure(true)
            .cookieJar(cookieJar)
            .build();


    private SSLContext getNewSslContext() {
        SSLContext sslContext;
        try {
            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, null, null);
        } catch (GeneralSecurityException e) {
            throw new AssertionError(); // The system has no TLS. Just give up.
        }
        return sslContext;
    }

    //Network
    @Override
    public NetworkResponse get(String url) throws Exception {
        return request(new NetworkRequest.Builder().url(url).build());
    }

    @Override
    public NetworkResponse request(NetworkRequest request) throws Exception {
        return request(request, this.client, null);
    }

    @Override
    public NetworkResponse request(NetworkRequest request, ProgressListener uploadProgressListener) throws Exception {
        return request(request, this.client, uploadProgressListener);
    }

    private Request.Builder prepareRequest(NetworkRequest request, ProgressListener uploadProgressListener) {
        String url = request.getUrl();
        if (request.getUrl().substring(0, 2).equals("//")) {
            url = "https:".concat(request.getUrl());
        }
        Log.d(LOG_TAG, "Request url " + request.getUrl());
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .header("Accept-Language", "ru-RU,ru;q=0.8,en-US;q=0.6,en;q=0.4")
                .header("User-Agent", USER_AGENT);
        if (request.getHeaders() != null) {
            for (Map.Entry<String, String> entry : request.getHeaders().entrySet()) {
                Log.d(LOG_TAG, "Header " + entry.getKey() + " : " +
                        (privateHeaders.contains(entry.getKey()) ? "private" : entry.getValue()));
                requestBuilder.header(entry.getKey(), entry.getValue());
            }
        }
        if (request.getFormHeaders() != null || request.getFile() != null) {
            Log.d(LOG_TAG, "Multipart " + request.isMultipartForm());
            if (request.getFormHeaders() != null) {
                for (Map.Entry<String, String> entry : request.getFormHeaders().entrySet()) {
                    Log.d(LOG_TAG, "Form header " + entry.getKey() + " : " + (privateHeaders.contains(entry.getKey())
                            ? "private" : entry.getValue()));
                }
            }
            if (request.getFile() != null) {
                Log.d(LOG_TAG, "Form file " + request.getFile().toString());
            }
            if (!request.isMultipartForm()) {
                if (request.getFormHeaders() != null) {
                    FormBody.Builder formBuilder = new FormBody.Builder();
                    for (Map.Entry<String, String> entry : request.getFormHeaders().entrySet()) {
                        formBuilder.add(entry.getKey(), entry.getValue());
                        if (request.getEncodedFormHeaders() != null && request.getEncodedFormHeaders().contains(entry.getKey())) {
                            formBuilder.addEncoded(entry.getKey(), entry.getValue());
                        } else {
                            formBuilder.add(entry.getKey(), entry.getValue());
                        }
                    }
                    FormBody formBody = formBuilder.build();
                    requestBuilder.post(formBody);
                }
            } else {
                MultipartBody.Builder multipartBuilder = new MultipartBody.Builder();
                multipartBuilder.setType(MultipartBody.FORM);
                if (request.getFormHeaders() != null) {
                    for (Map.Entry<String, String> entry : request.getFormHeaders().entrySet()) {
                        multipartBuilder.addFormDataPart(entry.getKey(), entry.getValue());
                    }
                }
                if (request.getFile() != null) {
                    MediaType type = MediaType.parse(request.getFile().getMimeType());
                    RequestBody requestBody = RequestBodyUtil
                            .create(type, request.getFile().getFileStream());
                    multipartBuilder.addFormDataPart(
                            request.getFile().getRequestName(),
                            request.getFile().getFileName(),
                            requestBody);
                }
                MultipartBody multipartBody = multipartBuilder.build();
                if (uploadProgressListener == null) {
                    requestBuilder.post(multipartBody);
                } else {
                    requestBuilder.post(new ProgressRequestBody(multipartBody, uploadProgressListener));
                }
            }
        }
        return requestBuilder;
    }

    public NetworkResponse request(NetworkRequest request, OkHttpClient client, ProgressListener uploadProgressListener) throws Exception {
        Request.Builder requestBuilder = prepareRequest(request, uploadProgressListener);
        NetworkResponse response = new NetworkResponse(request.getUrl());
        Response okHttpResponse = null;
        try {
            okHttpResponse = client.newCall(requestBuilder.build()).execute();
            if (!okHttpResponse.isSuccessful()) {
                if (okHttpResponse.code() == 403) {
                    final String content = okHttpResponse.body().string();
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(App.get(), content, Toast.LENGTH_LONG).show();
                        }
                    });
                }
                throw new OkHttpResponseException(okHttpResponse.code(), okHttpResponse.message(), request.getUrl());
            }

            response.setCode(okHttpResponse.code());
            response.setMessage(okHttpResponse.message());
            response.setRedirect(okHttpResponse.request().url().toString());

            if (!request.isWithoutBody()) {
                response.setBody(okHttpResponse.body().string());
                getCounts(response.getBody());
                checkForumErrors(response.getBody());
            }

            Log.d(LOG_TAG, "Response: " + response.toString());
        } finally {
            if (okHttpResponse != null)
                okHttpResponse.close();
        }
        return response;
    }

    public WebSocket createWebSocketConnection(WebSocketListener webSocketListener) {
        Request request = new Request.Builder()
                .url("ws://app.smartsworld.ru/ws/")
                .build();
        return webSocketClient.newWebSocket(request, webSocketListener);
    }

    private void checkForumErrors(String res) throws Exception {
        Matcher errorMatcher = errorPattern.matcher(res);
        if (errorMatcher.find()) {
            throw new OnlyShowException((errorMatcher.group(1)));
        }
    }

    private void getCounts(String res) {
        Matcher countsMatcher = countsPattern.matcher(res);

        if (countsMatcher.find()) {
            try {
                String tempGroup;
                tempGroup = countsMatcher.group(1);
                ClientHelper.setMentionsCount(tempGroup == null ? 0 : Integer.parseInt(tempGroup));

                tempGroup = countsMatcher.group(2);
                ClientHelper.setFavoritesCount(tempGroup == null ? 0 : Integer.parseInt(tempGroup));

                tempGroup = countsMatcher.group(3);
                ClientHelper.setQmsCount(tempGroup == null ? 0 : Integer.parseInt(tempGroup));
            } catch (Exception exception) {
                Log.d("WATAFUCK", res);
            }
            observerHandler.post(new Runnable() {
                @Override
                public void run() {
                    ClientHelper.get().notifyCountsChanged();
                }
            });
        }
    }

    public void clearCookies() {
        clientCookies.clear();
    }

    public void removeNetworkObserver(Observer observer) {
        networkObservables.deleteObserver(observer);
    }

    public void addNetworkObserver(Observer observer) {
        networkObservables.addObserver(observer);
    }

    public void notifyNetworkObservers(Boolean b) {
        networkObservables.notifyObservers(b);
    }

}
