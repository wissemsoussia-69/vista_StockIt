package com.example.stockit.model;

import java.util.List;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;

public interface ApiService {
    @GET("claims")
    Call<List<Claim>> getClaims();

    @POST("claims")
    Call<Claim> addClaim(@Body Claim claim);

    @GET("claims/{id}/messages")
    Call<List<ChatMessage>> getMessages(@Path("id") int claimId);

    @POST("claims/{id}/messages")
    Call<ChatMessage> addMessage(@Path("id") int claimId, @Body ChatMessage message);

    @POST("alerts")
    Call<Void> sendAlert(@Body java.util.Map<String, Object> data);
}
