package com.example.stockit.model;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.POST;

public interface ZycusApiService {
    @POST("api/v1/purchase-requests/create")
    Call<ZycusPR.Response> createPurchaseRequest(
        @Header("Authorization") String token,
        @Body ZycusPR request
    );
}
