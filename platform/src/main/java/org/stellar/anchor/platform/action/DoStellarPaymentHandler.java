package org.stellar.anchor.platform.action;

import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.DEPOSIT;
import static org.stellar.anchor.api.rpc.action.ActionMethod.DO_STELLAR_PAYMENT;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_ANCHOR;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_STELLAR;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_TRUST;

import java.time.Instant;
import java.util.Set;
import javax.validation.Validator;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.rpc.InvalidParamsException;
import org.stellar.anchor.api.exception.rpc.InvalidRequestException;
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind;
import org.stellar.anchor.api.rpc.action.ActionMethod;
import org.stellar.anchor.api.rpc.action.DoStellarPaymentRequest;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.config.CustodyConfig;
import org.stellar.anchor.custody.CustodyService;
import org.stellar.anchor.horizon.Horizon;
import org.stellar.anchor.platform.data.JdbcSep24Transaction;
import org.stellar.anchor.platform.data.JdbcSepTransaction;
import org.stellar.anchor.platform.data.JdbcTransactionPendingTrust;
import org.stellar.anchor.platform.data.JdbcTransactionPendingTrustRepo;
import org.stellar.anchor.sep24.Sep24TransactionStore;
import org.stellar.anchor.sep31.Sep31TransactionStore;

public class DoStellarPaymentHandler extends ActionHandler<DoStellarPaymentRequest> {

  private final CustodyService custodyService;
  private final CustodyConfig custodyConfig;
  private final JdbcTransactionPendingTrustRepo transactionPendingTrustRepo;

  public DoStellarPaymentHandler(
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      Validator validator,
      CustodyConfig custodyConfig,
      Horizon horizon,
      AssetService assetService,
      CustodyService custodyService,
      JdbcTransactionPendingTrustRepo transactionPendingTrustRepo) {
    super(txn24Store, txn31Store, validator, horizon, assetService, DoStellarPaymentRequest.class);
    this.custodyService = custodyService;
    this.custodyConfig = custodyConfig;
    this.transactionPendingTrustRepo = transactionPendingTrustRepo;
  }

  @Override
  protected void validate(JdbcSepTransaction txn, DoStellarPaymentRequest request)
      throws InvalidRequestException, InvalidParamsException {
    super.validate(txn, request);

    if (!custodyConfig.isCustodyIntegrationEnabled()) {
      throw new InvalidRequestException(
          String.format("Action[%s] requires disabled custody integration", getActionType()));
    }
  }

  @Override
  public ActionMethod getActionType() {
    return DO_STELLAR_PAYMENT;
  }

  @Override
  protected SepTransactionStatus getNextStatus(
      JdbcSepTransaction txn, DoStellarPaymentRequest request) {
    JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
    if (horizon.isTrustLineConfigured(txn24.getToAccount(), txn24.getAmountOutAsset())) {
      return PENDING_STELLAR;
    } else {
      return PENDING_TRUST;
    }
  }

  @Override
  protected Set<SepTransactionStatus> getSupportedStatuses(JdbcSepTransaction txn) {
    JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
    if (DEPOSIT == Kind.from(txn24.getKind())) {
      if (txn24.getTransferReceivedAt() != null) {
        return Set.of(PENDING_ANCHOR);
      }
    }
    return Set.of();
  }

  @Override
  protected Set<String> getSupportedProtocols() {
    return Set.of("24");
  }

  @Override
  protected void updateTransactionWithAction(
      JdbcSepTransaction txn, DoStellarPaymentRequest request) throws AnchorException {
    JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
    if (horizon.isTrustLineConfigured(txn24.getToAccount(), txn24.getAmountOutAsset())) {
      // TODO: Do we need to send request body?
      custodyService.createTransactionPayment(txn24.getId(), null);
    } else {
      transactionPendingTrustRepo.save(
          JdbcTransactionPendingTrust.builder()
              .id(txn24.getId())
              .createdAt(Instant.now())
              .count(0)
              .asset(txn24.getAmountOutAsset())
              .account(txn24.getToAccount())
              .build());
    }
  }
}
