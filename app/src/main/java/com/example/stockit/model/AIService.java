package com.example.stockit.model;

import java.util.Map;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.Headers;
import retrofit2.http.POST;

public interface AIService {

    @Headers("Content-Type: application/json")
    @POST("v1/chat/completions")
    Call<ResponseBody> getChatResponse(
        @Header("x-portkey-api-key") String apiKey,
        @Header("x-portkey-virtual-key") String virtualKey,
        @Body Map<String, Object> body
    );
}

