package br.edu.infnet.ecommerce.pagamento.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregate Root do contexto de Pagamento.
 *
 * Invariantes garantidos aqui dentro:
 * <ul>
 *   <li>só existe pagamento com valor e cartão válidos (garantido pelos Value Objects);</li>
 *   <li>um pagamento só pode ser aprovado ou recusado uma única vez;</li>
 *   <li>a política de valor, forma e limite vem antes de qualquer chamada externa.</li>
 * </ul>
 *
 * Pedido e Usuário são referenciados apenas por identidade. Nenhuma classe de
 * outro contexto aparece neste arquivo — é isso que permite mover o pacote
 * inteiro para outro processo sem reescrever o domínio.
 */
public class Pagamento {

    private static final Dinheiro LIMITE_POR_TRANSACAO =
            Dinheiro.reais(new BigDecimal("10000.00"));

    /** Máscara usada quando o cartão não pôde ser interpretado. */
    private static final String CARTAO_NAO_IDENTIFICADO = "****";

    private final PagamentoId id;
    private final long pedidoId;     // outro agregado: referência por identidade
    private final long usuarioId;    // outro agregado: referência por identidade
    private final Dinheiro valor;
    private final FormaPagamento forma;
    private final String cartaoMascarado;
    private final LocalDateTime criadoEm;

    private StatusPagamento status;
    private MotivoRecusa motivo;
    private String codigoAutorizacao;
    private LocalDateTime processadoEm;

    private Pagamento(PagamentoId id, long pedidoId, long usuarioId, Dinheiro valor,
                      FormaPagamento forma, String cartaoMascarado, StatusPagamento status,
                      LocalDateTime criadoEm) {
        this.id = Objects.requireNonNull(id, "id é obrigatório");
        this.pedidoId = pedidoId;
        this.usuarioId = usuarioId;
        this.valor = Objects.requireNonNull(valor, "valor é obrigatório");
        this.forma = Objects.requireNonNull(forma, "forma de pagamento é obrigatória");
        this.cartaoMascarado = cartaoMascarado;
        this.status = Objects.requireNonNull(status);
        this.criadoEm = Objects.requireNonNull(criadoEm);
    }

    /** Única forma de criar um pagamento novo. */
    public static Pagamento solicitar(long pedidoId, long usuarioId, Dinheiro valor,
                                      FormaPagamento forma, NumeroCartao cartao) {
        Objects.requireNonNull(cartao, "cartão é obrigatório");
        return new Pagamento(PagamentoId.novo(), pedidoId, usuarioId, valor, forma,
                cartao.mascarado(), StatusPagamento.PENDENTE, LocalDateTime.now());
    }

    /**
     * Pagamento que já nasce recusado porque o número do cartão não pôde
     * sequer ser interpretado. Existe para que cartão inválido continue
     * sendo uma recusa de negócio — registrada e auditável — como no
     * comportamento original do monólito, e não um erro técnico.
     */
    public static Pagamento recusarDeImediato(long pedidoId, long usuarioId, Dinheiro valor,
                                              FormaPagamento forma, MotivoRecusa motivo) {
        Pagamento pagamento = new Pagamento(PagamentoId.novo(), pedidoId, usuarioId, valor,
                forma, CARTAO_NAO_IDENTIFICADO, StatusPagamento.PENDENTE, LocalDateTime.now());
        pagamento.recusar(motivo);
        return pagamento;
    }

    /**
     * Reconstitui o agregado a partir do estado persistido.
     * Usado apenas pelo adaptador de persistência.
     */
    public static Pagamento reconstituir(PagamentoId id, long pedidoId, long usuarioId,
                                         Dinheiro valor, FormaPagamento forma,
                                         String cartaoMascarado, StatusPagamento status,
                                         MotivoRecusa motivo, String codigoAutorizacao,
                                         LocalDateTime criadoEm, LocalDateTime processadoEm) {
        Pagamento pagamento = new Pagamento(id, pedidoId, usuarioId, valor, forma,
                cartaoMascarado, status, criadoEm);
        pagamento.motivo = motivo;
        pagamento.codigoAutorizacao = codigoAutorizacao;
        pagamento.processadoEm = processadoEm;
        return pagamento;
    }

    /**
     * Regras que dependem apenas do próprio agregado, avaliadas antes de
     * qualquer integração externa. A ordem preserva a precedência do legado.
     */
    public Optional<MotivoRecusa> violacaoDePolitica() {
        if (!valor.ehPositivo()) {
            return Optional.of(MotivoRecusa.VALOR_INVALIDO);
        }
        if (forma != FormaPagamento.CARTAO) {
            return Optional.of(MotivoRecusa.FORMA_PAGAMENTO_NAO_SUPORTADA);
        }
        if (valor.maiorQue(LIMITE_POR_TRANSACAO)) {
            return Optional.of(MotivoRecusa.LIMITE_EXCEDIDO);
        }
        return Optional.empty();
    }

    public void aprovar(String codigoAutorizacao) {
        exigirPendente();
        this.codigoAutorizacao = Objects.requireNonNull(
                codigoAutorizacao, "código de autorização é obrigatório na aprovação");
        this.status = StatusPagamento.APROVADO;
        this.processadoEm = LocalDateTime.now();
    }

    public void recusar(MotivoRecusa motivo) {
        exigirPendente();
        this.motivo = Objects.requireNonNull(motivo, "motivo é obrigatório na recusa");
        this.status = StatusPagamento.RECUSADO;
        this.processadoEm = LocalDateTime.now();
    }

    private void exigirPendente() {
        if (status != StatusPagamento.PENDENTE) {
            throw new IllegalStateException("Pagamento já processado: " + id);
        }
    }

    public boolean aprovado() {
        return status == StatusPagamento.APROVADO;
    }

    public PagamentoId id()             { return id; }
    public long pedidoId()              { return pedidoId; }
    public long usuarioId()             { return usuarioId; }
    public Dinheiro valor()             { return valor; }
    public FormaPagamento forma()       { return forma; }
    public String cartaoMascarado()     { return cartaoMascarado; }
    public StatusPagamento status()     { return status; }
    public MotivoRecusa motivo()        { return motivo; }
    public String codigoAutorizacao()   { return codigoAutorizacao; }
    public LocalDateTime criadoEm()     { return criadoEm; }
    public LocalDateTime processadoEm() { return processadoEm; }
}
