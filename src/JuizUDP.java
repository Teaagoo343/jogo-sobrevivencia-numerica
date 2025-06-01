package src;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Classe que representa um Jogador no SERVIDOR (Juiz)
class Jogador {
    String nickname;
    InetAddress ip;
    int porta;
    int pontuacao = 0;
    int valorEscolhido = -1; // -1 significa que ainda não escolheu um número nesta rodada
    boolean emJogo = false; // Indica se o jogador optou por iniciar a partida

    public Jogador(String nickname, InetAddress ip, int porta) {
        this.nickname = nickname;
        this.ip = ip;
        this.porta = porta;
        this.pontuacao = 0;
    }

    public void enviarMensagem(DatagramSocket serverSocket, String mensagem) throws IOException {
        byte[] dados = mensagem.getBytes();
        DatagramPacket pacoteResposta = new DatagramPacket(dados, dados.length, this.ip, this.porta);
        serverSocket.send(pacoteResposta);
    }
}


public class JuizUDP {

    private static Map<String, Jogador> jogadoresConectados = new HashMap<>();
    private static final int PORTA_SERVIDOR = 3000;
    public static int N_JOGADORES_INICIAIS = 3; // Número de jogadores para iniciar o jogo
    private static DatagramSocket servidorSocketGlobal; // Socket global para enviar mensagens
    private static ExecutorService poolDeThreads = Executors.newFixedThreadPool(10); // Pool de threads para lidar com clientes

    // Um enum simples para representar o estado geral do jogo no servidor
    enum EstadoGlobalJogo { AGUARDANDO_JOGADORES, EM_ANDAMENTO, FIM_DE_PARTIDA }
    private static EstadoGlobalJogo estadoAtualJogo = EstadoGlobalJogo.AGUARDANDO_JOGADORES; // Estado inicial

    public static void main(String[] args) {
        try {
            servidorSocketGlobal = new DatagramSocket(PORTA_SERVIDOR); 

            System.out.println("Servidor do Jogo da Sobrevivência Numérica iniciado na porta " + PORTA_SERVIDOR);
            System.out.println("Aguardando jogadores...");

            byte[] bufferRecebimento = new byte[1024];

            while (true) {
                // Limpa o buffer a cada iteração para evitar lixo de mensagens anteriores
                for (int i = 0; i < bufferRecebimento.length; i++) {
                    bufferRecebimento[i] = 0;
                }
                
                DatagramPacket pacoteRecebido = new DatagramPacket(bufferRecebimento, bufferRecebimento.length);
                servidorSocketGlobal.receive(pacoteRecebido); 

                // Cria uma cópia dos dados do pacote para ser processada pela thread
                byte[] dadosCopiados = new byte[pacoteRecebido.getLength()];
                System.arraycopy(pacoteRecebido.getData(), pacoteRecebido.getOffset(), dadosCopiados, 0, pacoteRecebido.getLength());
                final DatagramPacket pacoteParaProcessar = new DatagramPacket(dadosCopiados, dadosCopiados.length, pacoteRecebido.getAddress(), pacoteRecebido.getPort());

                // Submete a tarefa de processamento do pacote para o pool de threads
                poolDeThreads.submit(() -> {
                    InetAddress enderecoCliente = pacoteParaProcessar.getAddress();
                    int portaCliente = pacoteParaProcessar.getPort();
                    String mensagemRecebida = new String(pacoteParaProcessar.getData(), 0, pacoteParaProcessar.getLength()).trim();

                    System.out.println("Mensagem recebida de " + enderecoCliente.getHostAddress() + ":" + portaCliente + " -> " + mensagemRecebida);

                    Jogador jogadorAtual = null;
                    // Procura o jogador existente com base no IP e Porta
                    synchronized (jogadoresConectados) {
                        for (Jogador j : jogadoresConectados.values()) {
                            if (j.ip.equals(enderecoCliente) && j.porta == portaCliente) {
                                jogadorAtual = j;
                                break;
                            }
                        }
                    }

                    if (jogadorAtual == null) { // Se não encontrou, é um novo cadastro
                        boolean ehNumero = false;
                        try {
                            Integer.parseInt(mensagemRecebida);
                            ehNumero = true;
                        } catch (NumberFormatException e) { /* Não é um número, pode ser nickname */ }

                        // Valida o nickname para cadastro
                        if (!mensagemRecebida.isEmpty() && !ehNumero && !jogadoresConectados.containsKey(mensagemRecebida)) {
                            synchronized (jogadoresConectados) {
                                Jogador novoJogador = new Jogador(mensagemRecebida, enderecoCliente, portaCliente);
                                jogadoresConectados.put(novoJogador.nickname, novoJogador);
                                System.out.println("Jogador(a): " + novoJogador.nickname + " se cadastrou. IP:" + novoJogador.ip.getHostAddress() + " Porta: " + novoJogador.porta);

                                String boasVindas = "Bem-vindo(a), " + novoJogador.nickname + ".\n" +
                                                    "Digite 1 - para ver as regras do jogo.\n" +
                                                    "Digite 2 - para iniciar o jogo.\n" +
                                                    "Digite 3 - para sair do jogo.\n" +
                                                    "O que deseja:";
                                enviarMensagemAoJogador(novoJogador, boasVindas);
                                
                                enviarFeedbackContagemJogadores(); // Envia feedback de contagem após cadastro
                            }
                        } else {
                            String msgErroCadastro = "Desculpe, a entrada para cadastro é inválida (vazia, nickname já usado ou é um número).";
                            try { // Envia erro de cadastro
                                byte[] dadosMsg = msgErroCadastro.getBytes();
                                DatagramPacket pacoteErro = new DatagramPacket(dadosMsg, dadosMsg.length, enderecoCliente, portaCliente);
                                servidorSocketGlobal.send(pacoteErro); 
                            } catch (IOException e) {
                                System.err.println("Erro ao enviar msg de erro de cadastro: " + e.getMessage());
                            }
                        }
                    } else { // Jogador já cadastrado (processar escolha de menu ou jogada)
                        try {
                            int escolha = Integer.parseInt(mensagemRecebida);
                            
                            // Se o jogo está em andamento, o jogador está em jogo, e a escolha é um número válido (0-100),
                            // então trata como JOGADA
                            if (estadoAtualJogo == EstadoGlobalJogo.EM_ANDAMENTO && jogadorAtual.emJogo && escolha >= 0 && escolha <= 100) {
                                jogadorAtual.valorEscolhido = escolha;
                                System.out.println("Jogador(a) " + jogadorAtual.nickname + " escolheu o número: " + jogadorAtual.valorEscolhido);
                                enviarMensagemAoJogador(jogadorAtual, "Você escolheu o número: " + escolha + ".\nEnviando o número escolhido para o servidor do jogo...\nAguardando os outros jogadores...");

                                // Verifica se todos os jogadores ativos já jogaram para processar a rodada
                                synchronized (jogadoresConectados) {
                                    long jogadoresQueJaJogaramNestaRodada = jogadoresConectados.values().stream()
                                                                            .filter(j -> j.emJogo && j.valorEscolhido != -1)
                                                                            .count();
                                    long jogadoresAtivos = jogadoresConectados.values().stream()
                                                                            .filter(j -> j.emJogo)
                                                                            .count();

                                    if (jogadoresQueJaJogaramNestaRodada == jogadoresAtivos && jogadoresAtivos >= 2) {
                                        processarRodada();
                                    }
                                }
                            } else {
                                // Caso contrário, processa a opção de menu
                                processarOpcaoMenu(escolha, jogadorAtual);
                            }
                        } catch (NumberFormatException e) {
                            // Se a entrada não for um número, é uma entrada inválida
                            enviarMensagemAoJogador(jogadorAtual, "Entrada inválida. Digite um número para a opção do menu ou para sua jogada.");
                            String menuMessage = "Bem-vindo(a)," + jogadorAtual.nickname + ".\n" +
                                                 "Digite 1 - para ver as regras do jogo.\n" +
                                                 "Digite 2 - para iniciar o jogo.\n" +
                                                 "Digite 3 - para sair do jogo.\n" +
                                                 "O que deseja:";
                            enviarMensagemAoJogador(jogadorAtual, menuMessage);
                        }
                    }
                }); 
            }

        } catch (SocketException e) {
            System.err.println("Erro ao criar/usar o socket do servidor: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("Erro de I/O no servidor: " + e.getMessage());
        } finally {
            if (servidorSocketGlobal != null && !servidorSocketGlobal.isClosed()) {
                servidorSocketGlobal.close();
            }
            poolDeThreads.shutdown();
            System.out.println("Servidor encerrado. Pool de threads desligado.");
        }
    }

    // Método auxiliar para enviar mensagens a um jogador, tratando exceções
    private static void enviarMensagemAoJogador(Jogador jogador, String mensagem) {
        try {
            jogador.enviarMensagem(servidorSocketGlobal, mensagem);
        } catch (IOException e) {
            System.err.println("Erro ao enviar mensagem para " + jogador.nickname + ": " + e.getMessage());
        }
    }

    // Centraliza a lógica de processar opções de menu
    private static void processarOpcaoMenu(int escolha, Jogador jogadorAtual) {
        switch (escolha) {
            case 1: // Ver regras
                String regras = "==\nRegras do Jogo da Sobrevivência Numérica:\n" +
                                "==\n" +
                                "No início três jogadores jogam, escolhendo um número entre 0 e 100.\n" +
                                "O Servidor do jogo receberá os três números escolhidos e calculará a média dos valores recebidos.\n" +
                                "O resultado das médias é então multiplicado por 0,8.\n" +
                                "Este novo valor resultante será o valor alvo.\n" +
                                "O valor alvo é comparado com os valores que cada jogador escolheu.\n" +
                                "O jogador que mais se distanciou do valor alvo, perde dois pontos.\n" +
                                "O jogador que mais se aproximou do valor alvo, não perde pontos.\n" +
                                "O outro jogador perde apenas um ponto.\n" +
                                "O jogador que chegar a menos seis pontos, primeiro, será eliminado definitivamente do jogo.\n" +
                                "Quando restarem apenas dois jogadores, as regras do jogo mudam.\n" +
                                "O jogador que mais se distanciar do valor alvo, perde um ponto.\n" +
                                "O outro jogador, não perde pontos.\n" +
                                "O jogador que primeiro chegar a menos seis pontos, será eliminado do jogo.\n" +
                                "O último jogador é declarado vencedor do Jogo da Sobrevivência Numérica.\n" +
                                "================================================================================";
                enviarMensagemAoJogador(jogadorAtual, regras);
                
                String menuMessageRegras = "Bem-vindo(a)," + jogadorAtual.nickname + ".\n" +
                                     "Digite 1 - para ver as regras do jogo.\n" +
                                     "Digite 2 - para iniciar o jogo.\n" +
                                     "Digite 3 - para sair do jogo.\n" +
                                     "O que deseja:";
                enviarMensagemAoJogador(jogadorAtual, menuMessageRegras);
                break;

            case 2: // INICIAR O JOGO
                if (!jogadorAtual.emJogo) {
                    synchronized (jogadoresConectados) {
                        long jogadoresAtivosNaPartida = jogadoresConectados.values().stream().filter(j -> j.emJogo).count();
                        if (jogadoresAtivosNaPartida < N_JOGADORES_INICIAIS) {
                            jogadorAtual.emJogo = true;
                            System.out.println("Jogador(a): " + jogadorAtual.nickname + " iniciou o jogo.");
                            jogadoresAtivosNaPartida++; // Atualiza a contagem após o jogador entrar

                            if (jogadoresAtivosNaPartida == N_JOGADORES_INICIAIS) {
                                estadoAtualJogo = EstadoGlobalJogo.EM_ANDAMENTO;
                                System.out.println(N_JOGADORES_INICIAIS + " jogadores prontos. Iniciando a partida...");
                                for (Jogador j : jogadoresConectados.values()) {
                                    if (j.emJogo) {
                                        enviarMensagemAoJogador(j, "Jogadores oponentes encontrados. Que comecem os jogos...");
                                        enviarMensagemAoJogador(j, "Escolha um número entre 0 e 100:");
                                    }
                                }
                            } else {
                                enviarMensagemAoJogador(jogadorAtual, "Aguardando mais jogadores para iniciar o jogo... (" + jogadoresAtivosNaPartida + "/" + N_JOGADORES_INICIAIS + " prontos)");
                            }
                        } else {
                            enviarMensagemAoJogador(jogadorAtual, "O jogo já está cheio. Por favor, tente novamente mais tarde.");
                        }
                    }
                } else {
                    enviarMensagemAoJogador(jogadorAtual, "Você já está no jogo! Por favor, escolha um número.");
                }
                enviarFeedbackContagemJogadores(); // Envia feedback de jogadores prontos para TODOS os jogadores cadastrados
                break;

            case 3: // Sair do jogo
                synchronized (jogadoresConectados) {
                    if (jogadorAtual.emJogo) {
                        jogadorAtual.emJogo = false; // Define como não em jogo
                    }
                    jogadoresConectados.remove(jogadorAtual.nickname);
                }
                System.out.println("Jogador(a): " + jogadorAtual.nickname + " saiu do jogo.");
                enviarMensagemAoJogador(jogadorAtual, "Você escolheu sair do jogo. Até mais!");
                
                long jogadoresRestantesEmJogo = jogadoresConectados.values().stream().filter(j -> j.emJogo).count();
                if (jogadoresRestantesEmJogo == 0) {
                    estadoAtualJogo = EstadoGlobalJogo.AGUARDANDO_JOGADORES;
                    System.out.println("Todos os jogadores saíram. Servidor pronto para nova partida.");
                } else {
                    enviarFeedbackContagemJogadores(); // Envia feedback atualizado após saída
                }
                // Se o jogo estava em andamento e um jogador sai, pode ser necessário reavaliar
                // e, se sobrar apenas um, declará-lo vencedor ou encerrar a partida
                if (estadoAtualJogo == EstadoGlobalJogo.EM_ANDAMENTO && jogadoresRestantesEmJogo == 1) {
                    Jogador vencedor = null;
                    synchronized (jogadoresConectados) {
                        for (Jogador j : jogadoresConectados.values()) {
                            if (j.emJogo) {
                                vencedor = j;
                                break;
                            }
                        }
                    }
                    if (vencedor != null) {
                        enviarMensagemAoJogador(vencedor, "Parabéns! Você foi o(a) vencedor(a)!");
                        System.out.println("Jogador(a) " + vencedor.nickname + " venceu o jogo!");
                    }
                    // Reinicia o estado do jogo
                    synchronized (jogadoresConectados) {
                        jogadoresConectados.clear();
                    }
                    estadoAtualJogo = EstadoGlobalJogo.FIM_DE_PARTIDA;
                    System.out.println("Fim de jogo. Servidor pronto para nova partida.");

                } else if (estadoAtualJogo == EstadoGlobalJogo.EM_ANDAMENTO && jogadoresRestantesEmJogo < 2) {
                    System.out.println("Número insuficiente de jogadores para continuar o jogo. Jogo encerrado.");
                    // Informa os jogadores remanescentes que o jogo acabou
                    synchronized (jogadoresConectados) {
                        for(Jogador j : jogadoresConectados.values()){
                            if(j.emJogo) {
                                enviarMensagemAoJogador(j, "Jogo encerrado devido a falta de jogadores.");
                            }
                            j.emJogo = false; // Garante que saiam do estado "emJogo"
                            j.pontuacao = 0; // Reinicia a pontuação
                        }
                        // Limpa jogadores conectados ou apenas define-os como não em jogo
                        // jogadoresConectados.clear(); // Depende se quer que eles possam iniciar um novo jogo sem reconectar
                    }
                    estadoAtualJogo = EstadoGlobalJogo.FIM_DE_PARTIDA;
                    System.out.println("Fim de jogo. Servidor pronto para nova partida.");
                }

                break;

            default: // Opção de menu inválida
                enviarMensagemAoJogador(jogadorAtual, "Opção de menu inválida. Digite 1, 2 ou 3.");
                String menuMessageDefault = "Bem-vindo(a)," + jogadorAtual.nickname + ".\n" +
                                     "Digite 1 - para ver as regras do jogo.\n" +
                                     "Digite 2 - para iniciar o jogo.\n" +
                                     "Digite 3 - para sair do jogo.\n" +
                                     "O que deseja:";
                enviarMensagemAoJogador(jogadorAtual, menuMessageDefault);
                break;
        }
    }


    // Envia o feedback de contagem de jogadores para todos os clientes cadastrados
    private static void enviarFeedbackContagemJogadores() {
        synchronized (jogadoresConectados) {
            long jogadoresCadastrados = jogadoresConectados.size();
            long jogadoresProntos = jogadoresConectados.values().stream().filter(j -> j.emJogo).count();
            String feedback = "Total de jogadores cadastrados: " + jogadoresCadastrados + ". " +
                              jogadoresProntos + "/" + N_JOGADORES_INICIAIS + " prontos para a partida.";
            
            for (Jogador j : jogadoresConectados.values()) {
                enviarMensagemAoJogador(j, feedback); 
            }
        }
    }


    // Processa uma rodada do jogo
    public static synchronized void processarRodada() {
        System.out.println("Iniciando processamento da rodada.");

        List<Jogador> jogadoresAtivosNaRodada = new ArrayList<>();
        synchronized (jogadoresConectados) {
            for (Jogador j : jogadoresConectados.values()) {
                if (j.emJogo) {
                    jogadoresAtivosNaRodada.add(j);
                }
            }
        }

        // Se menos de 2 jogadores estão ativos, encerra a partida ou aguarda mais
        if (jogadoresAtivosNaRodada.size() < 2) {
             System.out.println("Número insuficiente de jogadores para continuar o jogo. Jogo encerrado.");
             for(Jogador j : jogadoresAtivosNaRodada){
                 enviarMensagemAoJogador(j, "Jogo encerrado devido a falta de jogadores.");
             }
             synchronized (jogadoresConectados) {
                 jogadoresConectados.clear(); // Limpa todos os jogadores
             }
             estadoAtualJogo = EstadoGlobalJogo.FIM_DE_PARTIDA;
             System.out.println("Fim de jogo. Servidor pronto para nova partida.");
             return;
        }

        double soma = 0;
        int numJogadoresComNumero = 0;
        List<Jogador> jogadoresQueJogaram = new ArrayList<>();
        
        // Coleta os valores escolhidos pelos jogadores
        for (Jogador jogador : jogadoresAtivosNaRodada) {
            if (jogador.valorEscolhido != -1) { // Verifica se o jogador realmente escolheu um número nesta rodada
                soma += jogador.valorEscolhido;
                numJogadoresComNumero++;
                jogadoresQueJogaram.add(jogador);
            }
        }

        // Se ninguém escolheu um número, avisa e pede para jogar novamente
        if (numJogadoresComNumero == 0) {
            System.out.println("Nenhum jogador escolheu um número válido nesta rodada. Pulando cálculo.");
             for (Jogador jogador : jogadoresAtivosNaRodada) {
                enviarMensagemAoJogador(jogador, "Nenhum número válido escolhido na rodada. Placar permanece o mesmo.");
                enviarMensagemAoJogador(jogador, "Seu placar é: " + jogador.pontuacao);
                jogador.valorEscolhido = -1; // Reseta a escolha para a próxima rodada
                enviarMensagemAoJogador(jogador, "Escolha um número entre 0 e 100:");
            }
            return;
        }

        double media = soma / numJogadoresComNumero;
        double valorAlvo = media * 0.8;

        System.out.println("Média: " + media + ", Valor Alvo: " + valorAlvo);

        // Armazena as pontuações antigas para calcular a diferença
        Map<String, Integer> pontuacaoAntiga = new HashMap<>();
        for(Jogador j : jogadoresQueJogaram) {
            pontuacaoAntiga.put(j.nickname, j.pontuacao);
        }

        // Ordena os jogadores pela proximidade do valor alvo
        jogadoresQueJogaram.sort(Comparator.comparingDouble(j -> Math.abs(j.valorEscolhido - valorAlvo)));

        // Aplica as regras de pontuação com base no número de jogadores que jogaram
        if (jogadoresQueJogaram.size() == 3) {
            // O mais próximo não perde pontos
            // O do meio perde 1 ponto
            jogadoresQueJogaram.get(1).pontuacao--; 
            // O mais distante perde 2 pontos
            jogadoresQueJogaram.get(2).pontuacao -= 2;
        } else if (jogadoresQueJogaram.size() == 2) {
            // O mais próximo não perde pontos
            // O mais distante perde 1 ponto
            jogadoresQueJogaram.get(1).pontuacao--;
        }
        
        // Informa os jogadores sobre a pontuação perdida e o placar atual
        List<String> nicknamesEliminados = new ArrayList<>();
        for (Jogador jogador : jogadoresAtivosNaRodada) { // Itera sobre todos os ativos na rodada (não apenas os que jogaram)
            int perdidos = pontuacaoAntiga.getOrDefault(jogador.nickname, jogador.pontuacao) - jogador.pontuacao; // Calcula a perda
            
            if (perdidos > 0) {
                enviarMensagemAoJogador(jogador, "Você perdeu " + perdidos + " ponto(s) nesta rodada.");
            } else if (numJogadoresComNumero > 0) { // Se houve jogada válida na rodada
                enviarMensagemAoJogador(jogador, "Você não perdeu pontos nesta rodada.");
            } else { // Caso não tenha jogado e ninguém jogou
                enviarMensagemAoJogador(jogador, "Nenhum número válido escolhido na rodada. Placar permanece o mesmo.");
            }

            enviarMensagemAoJogador(jogador, "Seu placar atual é: " + jogador.pontuacao);
            System.out.println("Placar de " + jogador.nickname + ": " + jogador.pontuacao);

            // Verifica se o jogador foi eliminado
            if (jogador.pontuacao <= -6) {
                enviarMensagemAoJogador(jogador, "Você foi eliminado(a)!");
                nicknamesEliminados.add(jogador.nickname);
                System.out.println("Jogador(a) " + jogador.nickname + " foi eliminado(a).");
            }
            jogador.valorEscolhido = -1; // Reseta o valor escolhido para a próxima rodada
        }
        
        // Mensagem de fim de rodada para todos os jogadores ativos
        for (Jogador jogador : jogadoresAtivosNaRodada) {
             enviarMensagemAoJogador(jogador, "------------------------------------\nFim da Rodada. Aguardando próxima jogada...");
        }

        // Remove jogadores eliminados
        synchronized (jogadoresConectados) {
            for (String nickname : nicknamesEliminados) {
                jogadoresConectados.remove(nickname);
            }
        }

        long jogadoresAtualmenteEmJogo = jogadoresConectados.values().stream().filter(j -> j.emJogo).count();

        // Verifica as condições de vitória ou fim de jogo
        if (jogadoresAtualmenteEmJogo == 1) {
            Jogador vencedor = null;
            synchronized (jogadoresConectados) {
                for (Jogador j : jogadoresConectados.values()) {
                    if (j.emJogo) {
                        vencedor = j;
                        break;
                    }
                }
            }
            if (vencedor != null) {
                enviarMensagemAoJogador(vencedor, "Parabéns! Você foi o(a) vencedor(a)!");
                System.out.println("Jogador(a) " + vencedor.nickname + " venceu o jogo!");
            }

            // Limpa jogadores para uma nova partida
            synchronized (jogadoresConectados) {
                jogadoresConectados.clear();
            }
            estadoAtualJogo = EstadoGlobalJogo.FIM_DE_PARTIDA;
            System.out.println("Fim de jogo. Servidor pronto para nova partida.");
        } else if (jogadoresAtualmenteEmJogo == 0 && !jogadoresConectados.isEmpty()) { // Ninguém mais em jogo, mas ainda há cadastrados
            System.out.println("Todos os jogadores que estavam em jogo foram eliminados. Fim da partida.");
            estadoAtualJogo = EstadoGlobalJogo.FIM_DE_PARTIDA;
            synchronized (jogadoresConectados) {
                for (Jogador j : jogadoresConectados.values()) {
                    j.emJogo = false; // Define como não em jogo
                    j.pontuacao = 0; // Reinicia a pontuação
                    enviarMensagemAoJogador(j, "A partida atual foi encerrada. Digite 2 para iniciar uma nova partida.");
                }
            }
            System.out.println("Servidor pronto para nova partida com jogadores existentes.");
        } else if (jogadoresConectados.isEmpty()) { // Todos os jogadores foram eliminados ou saíram
            System.out.println("Todos os jogadores foram eliminados ou saíram. Fim de jogo.");
            estadoAtualJogo = EstadoGlobalJogo.FIM_DE_PARTIDA;
            System.out.println("Fim de jogo. Servidor pronto para nova partida.");
        }
         else {
            // Se o jogo continua, pede a próxima jogada
            estadoAtualJogo = EstadoGlobalJogo.EM_ANDAMENTO;
            synchronized (jogadoresConectados) {
                for (Jogador jogador : jogadoresConectados.values()) {
                    if (jogador.emJogo) {
                        enviarMensagemAoJogador(jogador, "Escolha um número entre 0 e 100:");
                    }
                }
            }
        }
    }
}