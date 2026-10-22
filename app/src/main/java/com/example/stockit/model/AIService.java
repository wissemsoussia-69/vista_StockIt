package com.example.stockit.model;

import java.util.Map;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.Headers;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface AIService {
    
    // Appel vers Portkey / Bedrock pour le Chatbot
    @Headers("Content-Type: application/json")
    @POST("v1/chat/completions")
    Call<ResponseBody> getChatResponse(
        @Header("x-portkey-api-key") String apiKey,
        @Header("x-portkey-virtual-key") String virtualKey,
        @Body Map<String, Object> body
    );

    // Analyse de document (Facture)
    @Headers("Content-Type: application/json")
    @POST("v1/embeddings") 
    Call<ResponseBody> analyzeInvoice(
        @Header("x-portkey-api-key") String apiKey,
        @Body Map<String, Object> body
    );

    @POST("models/{modelId}:generateContent")
    Call<ResponseBody> generateGeminiContent(
        @Path("modelId") String modelId,
        @Query("key") String apiKey,
        @Body Map<String, Object> body
    );

    // Hugging Face Inference API
    @POST("models/{modelId}")
    Call<ResponseBody> queryHuggingFace(
        @Header("Authorization") String token,
        @Path("modelId") String modelId,
        @Body RequestBody image
    );
}
