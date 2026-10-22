package com.example.stockit.model;

import java.util.List;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.POST;

public interface HuggingFaceApiService {
    @POST("models/google/vit-base-patch16-224")
    Call<List<HFVisionResponse>> identifyObject(
            @Header("Authorization") String token,
            @Header("x-wait-for-model") String waitForModel,
            @Body RequestBody imageBytes
    );
}
