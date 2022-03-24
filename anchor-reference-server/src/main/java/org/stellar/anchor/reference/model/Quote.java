package org.stellar.anchor.reference.model;

import java.time.LocalDateTime;
import java.util.UUID;
import javax.persistence.Entity;
import javax.persistence.Id;
import lombok.Data;
import org.stellar.platform.apis.callbacks.requests.GetRateRequest;

@Data
@Entity
public class Quote {
  @Id String id;

  String price;

  LocalDateTime expiresAt;

  LocalDateTime createdAt;

  String sellAsset;

  String sellAmount;

  String sellDeliveryMethod;

  String buyAsset;

  String buyAmount;

  String buyDeliveryMethod;

  String countryCode;

  String clientDomain;

  String stellarAccount;

  String memo;

  String memoType;

  String transactionId;

  public static Quote of(GetRateRequest request, String price) {
    Quote quote = new Quote();
    quote.setId(UUID.randomUUID().toString());
    quote.setSellAsset(request.getSellAsset());
    quote.setSellAmount(request.getSellAmount());
    quote.setSellDeliveryMethod(request.getSellDeliveryMethod());
    quote.setBuyAsset(request.getBuyAsset());
    quote.setBuyAmount(request.getBuyAmount());
    quote.setSellDeliveryMethod(request.getSellDeliveryMethod());
    quote.setCountryCode(request.getCountryCode());
    quote.setCreatedAt(LocalDateTime.now());
    quote.setPrice(price);
    quote.setStellarAccount(request.getAccount());
    quote.setMemo(request.getMemo());
    quote.setMemoType(request.getMemoType());

    return quote;
  }
}