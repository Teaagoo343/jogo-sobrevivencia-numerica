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
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Classe que representa um Jogador no SERVIDOR (Juiz)
class Jogador {
    String nickname;
    InetAddress ip;
    int porta;
    int pontuacao = 0;
    int valorEscolhido = -1;
    boolean emJogo = false; // Indica se o jogador optou por iniciar a partida
    // Estado atual do jogador para o servidor saber o que esperar dele
    enum EstadoJogador { CADASTRADO, AGUARDANDO_INICIO, JOGANDO, ELIMINADO, VENCEDOR }
    EstadoJogador estado = EstadoJogador.CADASTRADO; // Começa como cadastrado

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
    public static int N_JOGADORES_INICIAIS = 3;
    public static Semaphore semaforoPartida = new Semaphore(N_JOGADORES_INICIAIS);
    public static CyclicBarrier barreiraRodada;
    private static DatagramSocket servidorSocketGlobal;

    private static ExecutorService poolDeThreads = Executors.newFixedThreadPool(10);

    // Um enum para representar o estado geral do jogo no servidor
    enum EstadoGlobalJogo { AGUARDANDO_JOGADORES, INICIANDO_PARTIDA, EM_ANDAMENTO, FIM_DE_PARTIDA }
    private static EstadoGlobalJogo estadoAtualJogo = EstadoGlobalJogo.AGUARDANDO_JOGADORES; // Estado inicial

    public static void configurarBarreira() {
        Runnable acaoBarreira = () -> {
            System.out.println("DEBUG: Barreira quebrada. Processando rodada...");
            try {
                processarRodada(servidorSocketGlobal);
            } catch (IOException e) {
                System.err.println("Erro ao processar rodada após barreira: " + e.getMessage());
            }
        };

        long jogadoresEmJogo = jogadoresConectados.values().stream()
                                .filter(j -> j.emJogo)
                                .count();

        if (jogadoresEmJogo > 0) {
            barreiraRodada = new CyclicBarrier((int) jogadoresEmJogo, acaoBarreira);
            System.out.println("DEBUG: Barreira configurada para " + jogadoresEmJogo + " jogadores em jogo.");
        } else {
            barreiraRodada = null;
            System.out.println("DEBUG: Nenhuma barreira configurada, nenhum jogador ativo em jogo.");
        }
    }


    public static void main(String[] args) {
        DatagramSocket serverSocket = null; 

        try {
            servidorSocketGlobal = new DatagramSocket(PORTA_SERVIDOR); 

            System.out.println("Servidor do Jogo da Sobrevivência Numérica iniciado na porta " + PORTA_SERVIDOR);
            System.out.println("Aguardando jogadores...");

            byte[] bufferRecebimento = new byte[1024];

            while (true) {
                for (int i = 0; i < bufferRecebimento.length; i++) {
                    bufferRecebimento[i] = 0;
                }
                
                DatagramPacket pacoteRecebido = new DatagramPacket(bufferRecebimento, bufferRecebimento.length);
                servidorSocketGlobal.receive(pacoteRecebido); 

                byte[] dadosCopiados = new byte[pacoteRecebido.getLength()];
                System.arraycopy(pacoteRecebido.getData(), pacoteRecebido.getOffset(), dadosCopiados, 0, pacoteRecebido.getLength());
                final DatagramPacket pacoteParaProcessar = new DatagramPacket(dadosCopiados, dadosCopiados.length, pacoteRecebido.getAddress(), pacoteRecebido.getPort());

                poolDeThreads.submit(() -> {
                    InetAddress enderecoCliente = pacoteParaProcessar.getAddress();
                    int portaCliente = pacoteParaProcessar.getPort();
                    String mensagemRecebida = new String(pacoteParaProcessar.getData(), 0, pacoteParaProcessar.getLength()).trim();

                    System.out.println("DEBUG (Thread): Mensagem recebida de " + enderecoCliente.getHostAddress() + ":" + portaCliente + " -> " + mensagemRecebida);

                    Jogador jogadorAtual = null;
                    synchronized (jogadoresConectados) {
                        for (Jogador j : jogadoresConectados.values()) {
                            if (j.ip.equals(enderecoCliente) && j.porta == portaCliente) {
                                jogadorAtual = j;
                                break;
                            }
                        }
                    }

                    if (jogadorAtual == null) { // NOVO CADASTRO
                        boolean ehNumero = false;
                        try {
                            Integer.parseInt(mensagemRecebida);
                            ehNumero = true;
                        } catch (NumberFormatException e) { } // Não é um número, pode ser nickname

                        if (!mensagemRecebida.isEmpty() && !ehNumero && !jogadoresConectados.containsKey(mensagemRecebida)) { // Nickname não duplicado e não é número
                            synchronized (jogadoresConectados) {
                                Jogador novoJogador = new Jogador(mensagemRecebida, enderecoCliente, portaCliente);
                                jogadoresConectados.put(novoJogador.nickname, novoJogador);
                                System.out.println("Jogador(a): " + novoJogador.nickname + " se cadastrou. IP:" + novoJogador.ip.getHostAddress() + " Porta: " + novoJogador.porta);

                                String boasVindas = "Bem-vindo(a), " + novoJogador.nickname + ".\n" +
                                                    "Digite 1 - para ver as regras do jogo.\n" +
                                                    "Digite 2 - para iniciar o jogo.\n" +
                                                    "Digite 3 - para sair do jogo.\n" +
                                                    "O que deseja:";
                                try {
                                    novoJogador.enviarMensagem(servidorSocketGlobal, boasVindas);
                                } catch (IOException e) {
                                    System.err.println("Erro ao enviar boas-vindas: " + e.getMessage());
                                }
                                
                                try { // <-- NOVO try-catch AQUI para enviarFeedbackContagemJogadores
                                    enviarFeedbackContagemJogadores(servidorSocketGlobal);
                                } catch (IOException e) {
                                    System.err.println("Erro ao enviar feedback de contagem de jogadores (cadastro): " + e.getMessage());
                                }
                            }
                        } else {
                            String msgErroCadastro = "Desculpe, a entrada para cadastro é inválida (vazia, nickname já usado ou é um número).";
                            try {
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
                            String mensagemParaJogador = "";

                            // Lógica de estado para diferenciar opções de menu de jogadas
                            if (estadoAtualJogo == EstadoGlobalJogo.EM_ANDAMENTO && jogadorAtual.emJogo && escolha >= 0 && escolha <= 100) {
                                // Se o jogo está em andamento, o jogador está em jogo, e a escolha é um número válido (0-100),
                                // então trata como JOGADA
                                jogadorAtual.valorEscolhido = escolha;
                                System.out.println("Jogador(a) " + jogadorAtual.nickname + " escolheu o número: " + jogadorAtual.valorEscolhido);
                                mensagemParaJogador = "Você escolheu o número: " + escolha + ".\nEnviando o número escolhido para o servidor do jogo...\nAguardando os outros jogadores...";
                                try {
                                    jogadorAtual.enviarMensagem(servidorSocketGlobal, mensagemParaJogador);
                                } catch (IOException e) {
                                    System.err.println("Erro ao enviar número escolhido: " + e.getMessage());
                                }

                                try {
                                    if (barreiraRodada != null) {
                                        barreiraRodada.await();
                                    } else {
                                        mensagemParaJogador = "O jogo ainda não começou oficialmente. Aguarde outros jogadores iniciarem.";
                                        try {
                                            jogadorAtual.enviarMensagem(servidorSocketGlobal, mensagemParaJogador);
                                        } catch (IOException e) {
                                            System.err.println("Erro ao enviar jogo não iniciado (await): " + e.getMessage());
                                        }
                                    }
                                } catch (BrokenBarrierException | InterruptedException e) {
                                    System.err.println("DEBUG: Erro na barreira ou barreira quebrada antes da hora: " + e.getMessage());
                                    mensagemParaJogador = "Ocorreu um erro na rodada ou um jogador saiu. Por favor, escolha um número novamente ou uma opção de menu.";
                                    try {
                                        jogadorAtual.enviarMensagem(servidorSocketGlobal, mensagemParaJogador);
                                    } catch (IOException eIO) { System.err.println("Erro ao enviar erro de barreira: " + eIO.getMessage()); }
                                    
                                    mensagemParaJogador = "Bem-vindo(a)," + jogadorAtual.nickname + ".\n" +
                                                          "Digite 1 - para ver as regras do jogo.\n" +
                                                          "Digite 2 - para iniciar o jogo.\n" +
                                                          "Digite 3 - para sair do jogo.\n" +
                                                          "O que deseja:";
                                    try {
                                        jogadorAtual.enviarMensagem(servidorSocketGlobal, mensagemParaJogador);
                                    } catch (IOException eIO) { System.err.println("Erro ao reenviar menu (barreira erro): " + eIO.getMessage()); }
                                }
                            } else {
                                // AQUI ESTÁ A CORREÇÃO PRINCIPAL: Try-catch em torno de processarOpcaoMenu
                                try { 
                                    processarOpcaoMenu(escolha, jogadorAtual, servidorSocketGlobal);
                                } catch (IOException e) {
                                    System.err.println("Erro ao processar opção de menu para " + jogadorAtual.nickname + ": " + e.getMessage());
                                    // Se ocorrer um erro aqui, reenvia o menu como fallback
                                    mensagemParaJogador = "Ocorreu um erro ao processar sua opção. Por favor, tente novamente.\n" +
                                                          "Bem-vindo(a)," + jogadorAtual.nickname + ".\n" +
                                                          "Digite 1 - para ver as regras do jogo.\n" +
                                                          "Digite 2 - para iniciar o jogo.\n" +
                                                          "Digite 3 - para sair do jogo.\n" +
                                                          "O que deseja:";
                                    try {
                                        jogadorAtual.enviarMensagem(servidorSocketGlobal, mensagemParaJogador);
                                    } catch (IOException e2) { /* Ignorar erro adicional */ }
                                }
                            }
                        } catch (NumberFormatException e) {
                            String msgInvalida = "Entrada inválida. Digite um número para a opção do menu ou para sua jogada.";
                            try {
                                jogadorAtual.enviarMensagem(servidorSocketGlobal, msgInvalida);
                            } catch (IOException eIO) { System.err.println("Erro ao enviar entrada inválida (NumberFormatException): " + eIO.getMessage()); }
                            
                            String menuMessage = "Bem-vindo(a)," + jogadorAtual.nickname + ".\n" +
                                                 "Digite 1 - para ver as regras do jogo.\n" +
                                                 "Digite 2 - para iniciar o jogo.\n" +
                                                 "Digite 3 - para sair do jogo.\n" +
                                                 "O que deseja:";
                            try {
                                jogadorAtual.enviarMensagem(servidorSocketGlobal, menuMessage);
                            } catch (IOException eIO) { System.err.println("Erro ao reenviar menu (NumberFormatException): " + eIO.getMessage()); }
                        }
                    }
                }); // Fim da submissão da tarefa ao pool de threads
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

    // NOVO MÉTODO: Centraliza a lógica de processar opções de menu
    private static void processarOpcaoMenu(int escolha, Jogador jogadorAtual, DatagramSocket serverSocket) throws IOException {
        String mensagemParaJogador = "";
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
                try {
                    jogadorAtual.enviarMensagem(serverSocket, regras);
                } catch (IOException e) { System.err.println("Erro ao enviar regras: " + e.getMessage()); }
                
                mensagemParaJogador = "Bem-vindo(a)," + jogadorAtual.nickname + ".\n" +
                                     "Digite 1 - para ver as regras do jogo.\n" +
                                     "Digite 2 - para iniciar o jogo.\n" +
                                     "Digite 3 - para sair do jogo.\n" +
                                     "O que deseja:";
                try {
                    jogadorAtual.enviarMensagem(serverSocket, mensagemParaJogador);
                } catch (IOException e) { System.err.println("Erro ao reenviar menu (regras): " + e.getMessage()); }
                break;

            case 2: // INICIAR O JOGO
                if (!jogadorAtual.emJogo) {
                    if (semaforoPartida.tryAcquire()) {
                        jogadorAtual.emJogo = true;
                        System.out.println("Jogador(a): " + jogadorAtual.nickname + " iniciou o jogo.");
                        jogadorAtual.estado = Jogador.EstadoJogador.JOGANDO;
                        
                        long jogadoresAtivosNaPartida = jogadoresConectados.values().stream().filter(j -> j.emJogo).count();

                        if (jogadoresAtivosNaPartida == N_JOGADORES_INICIAIS) {
                            estadoAtualJogo = EstadoGlobalJogo.EM_ANDAMENTO;
                            System.out.println("DEBUG: " + N_JOGADORES_INICIAIS + " jogadores prontos. Iniciando a barreira...");
                            configurarBarreira();
                            synchronized (jogadoresConectados) {
                                for (Jogador j : jogadoresConectados.values()) {
                                    if (j.emJogo) {
                                        try {
                                            j.enviarMensagem(serverSocket, "Jogadores oponentes encontrados. Que comecem os jogos...");
                                            j.enviarMensagem(serverSocket, "Escolha um número entre 0 e 100:");
                                        } catch (IOException e) { System.err.println("Erro ao enviar início de jogo: " + e.getMessage()); }
                                    }
                                }
                            }
                        } else {
                            mensagemParaJogador = "Aguardando mais jogadores para iniciar o jogo... (" + jogadoresAtivosNaPartida + "/" + N_JOGADORES_INICIAIS + " prontos)";
                            try {
                                jogadorAtual.enviarMensagem(serverSocket, mensagemParaJogador);
                            } catch (IOException e) { System.err.println("Erro ao enviar aguardando jogadores: " + e.getMessage()); }
                        }
                    } else {
                        mensagemParaJogador = "O jogo já está cheio. Por favor, tente novamente mais tarde.";
                        try {
                            jogadorAtual.enviarMensagem(serverSocket, mensagemParaJogador);
                        } catch (IOException e) { System.err.println("Erro ao enviar jogo cheio: " + e.getMessage()); }
                    }
                } else {
                    mensagemParaJogador = "Você já está no jogo! Por favor, escolha um número.";
                    try {
                        jogadorAtual.enviarMensagem(serverSocket, mensagemParaJogador);
                    } catch (IOException e) { System.err.println("Erro ao enviar já em jogo: " + e.getMessage()); }
                }
                // Envia feedback de jogadores prontos para TODOS os jogadores cadastrados (após alguém escolher 2)
                try { // <-- NOVO try-catch AQUI para enviarFeedbackContagemJogadores
                    enviarFeedbackContagemJogadores(serverSocket);
                } catch (IOException e) {
                    System.err.println("Erro ao enviar feedback de contagem de jogadores (iniciar jogo): " + e.getMessage());
                }
                break;

            case 3: // Sair do jogo
                if (jogadorAtual.emJogo) {
                    semaforoPartida.release(); 
                    jogadorAtual.emJogo = false;
                }
                synchronized (jogadoresConectados) {
                    jogadoresConectados.remove(jogadorAtual.nickname);
                }
                System.out.println("Jogador(a): " + jogadorAtual.nickname + " saiu do jogo.");
                mensagemParaJogador = "Você escolheu sair do jogo. Até mais!";
                try {
                    jogadorAtual.enviarMensagem(serverSocket, mensagemParaJogador);
                } catch (IOException e) { System.err.println("Erro ao enviar mensagem de saída: " + e.getMessage()); }
                
                long jogadoresRestantesEmJogo = jogadoresConectados.values().stream().filter(j -> j.emJogo).count();
                if (barreiraRodada != null && jogadoresRestantesEmJogo > 0) {
                    configurarBarreira(); 
                } else if (jogadoresRestantesEmJogo == 0 && barreiraRodada != null) {
                    barreiraRodada.reset();
                    barreiraRodada = null;
                }

                if (jogadoresConectados.isEmpty()) {
                    estadoAtualJogo = EstadoGlobalJogo.AGUARDANDO_JOGADORES;
                    semaforoPartida = new Semaphore(N_JOGADORES_INICIAIS);
                    barreiraRodada = null;
                    System.out.println("Todos os jogadores saíram. Servidor pronto para nova partida.");
                } else {
                    try { // <-- NOVO try-catch AQUI para enviarFeedbackContagemJogadores
                        enviarFeedbackContagemJogadores(serverSocket); // Envia feedback atualizado após saída
                    } catch (IOException e) {
                        System.err.println("Erro ao enviar feedback de contagem de jogadores (sair): " + e.getMessage());
                    }
                }
                break;

            default: // Opção de menu inválida
                mensagemParaJogador = "Opção de menu inválida. Digite 1, 2 ou 3.";
                try {
                    jogadorAtual.enviarMensagem(serverSocket, mensagemParaJogador);
                } catch (IOException e) { System.err.println("Erro ao enviar opção inválida: " + e.getMessage()); }
                
                mensagemParaJogador = "Bem-vindo(a)," + jogadorAtual.nickname + ".\n" +
                                     "Digite 1 - para ver as regras do jogo.\n" +
                                     "Digite 2 - para iniciar o jogo.\n" +
                                     "Digite 3 - para sair do jogo.\n" +
                                     "O que deseja:";
                try {
                    jogadorAtual.enviarMensagem(serverSocket, mensagemParaJogador);
                } catch (IOException e) { System.err.println("Erro ao reenviar menu (opção inválida): " + e.getMessage()); }
                break;
        }
    }


    // NOVO MÉTODO: Envia o feedback de contagem de jogadores para todos os clientes cadastrados
    // Este método AGORA declara throws IOException, então as chamadas a ele precisam de try-catch.
    private static void enviarFeedbackContagemJogadores(DatagramSocket serverSocket) throws IOException {
        synchronized (jogadoresConectados) {
            long jogadoresCadastrados = jogadoresConectados.size();
            long jogadoresProntos = jogadoresConectados.values().stream().filter(j -> j.emJogo).count();
            String feedback = "DEBUG: " + jogadoresCadastrados + " jogadores cadastrados. " +
                              jogadoresProntos + "/" + N_JOGADORES_INICIAIS + " prontos para a partida.";
            
            for (Jogador j : jogadoresConectados.values()) {
                // AQUI: A chamada a enviarMensagem dentro deste loop já está em um try-catch.
                // Mas o método enviarFeedbackContagemJogadores AGORA declara throws IOException,
                // então suas chamadas externas precisarão de try-catch.
                j.enviarMensagem(serverSocket, feedback); 
            }
        }
    }


    public static synchronized void processarRodada(DatagramSocket serverSocket) throws IOException {
        System.out.println("DEBUG: Iniciando processamento da rodada.");

        List<Jogador> jogadoresAtivosNaRodada = new ArrayList<>();
        synchronized (jogadoresConectados) {
            for (Jogador j : jogadoresConectados.values()) {
                if (j.emJogo) {
                    jogadoresAtivosNaRodada.add(j);
                }
            }
        }

        if (jogadoresAtivosNaRodada.size() < 2) {
             System.out.println("DEBUG: Número insuficiente de jogadores para continuar o jogo. Jogo encerrado.");
             for(Jogador j : jogadoresAtivosNaRodada){
                 try {
                     j.enviarMensagem(serverSocket, "Jogo encerrado devido a falta de jogadores.");
                 } catch (IOException e) {
                     System.err.println("Erro ao enviar encerramento de jogo por falta de jogadores: " + e.getMessage());
                 }
             }
             synchronized (jogadoresConectados) {
                 jogadoresConectados.clear();
             }
             semaforoPartida = new Semaphore(N_JOGADORES_INICIAIS);
             barreiraRodada = null;
             estadoAtualJogo = EstadoGlobalJogo.FIM_DE_PARTIDA;
             System.out.println("DEBUG: Fim de jogo. Servidor pronto para nova partida.");
             return;
        }

        double soma = 0;
        int numJogadoresComNumero = 0;
        List<Jogador> jogadoresQueJogaram = new ArrayList<>();
        
        for (Jogador jogador : jogadoresAtivosNaRodada) {
            if (jogador.valorEscolhido != -1) {
                soma += jogador.valorEscolhido;
                numJogadoresComNumero++;
                jogadoresQueJogaram.add(jogador);
            }
        }

        if (numJogadoresComNumero == 0) {
            System.out.println("DEBUG: Nenhum jogador escolheu um número válido nesta rodada. Pulando cálculo.");
             for (Jogador jogador : jogadoresAtivosNaRodada) {
                try {
                    jogador.enviarMensagem(serverSocket, "Nenhum número válido escolhido na rodada. Placar permanece o mesmo.");
                    jogador.enviarMensagem(serverSocket, "Seu placar é: " + jogador.pontuacao);
                } catch (IOException e) {
                    System.err.println("Erro ao enviar mensagem de rodada sem número: " + e.getMessage());
                }
            }
            for (Jogador jogador : jogadoresAtivosNaRodada) {
                jogador.valorEscolhido = -1;
            }
            configurarBarreira();
            return;
        }

        double media = soma / numJogadoresComNumero;
        double valorAlvo = media * 0.8;

        System.out.println("DEBUG: Média: " + media + ", Valor Alvo: " + valorAlvo);

        Map<String, Integer> pontosPerdidosRodada = new HashMap<>();
        
        jogadoresQueJogaram.sort(Comparator.comparingDouble(j -> Math.abs(j.valorEscolhido - valorAlvo)));

        Map<String, Integer> pontuacaoAntiga = new HashMap<>();
        for(Jogador j : jogadoresQueJogaram) {
            pontuacaoAntiga.put(j.nickname, j.pontuacao);
        }

        if (jogadoresQueJogaram.size() == 3) {
            jogadoresQueJogaram.get(0).pontuacao += 0;
            jogadoresQueJogaram.get(1).pontuacao--;
            jogadoresQueJogaram.get(2).pontuacao -= 2;
        } else if (jogadoresQueJogaram.size() == 2) {
            jogadoresQueJogaram.get(0).pontuacao += 0;
            jogadoresQueJogaram.get(1).pontuacao--;
        }
        
        for(Jogador j : jogadoresQueJogaram) {
            int perdidos = pontuacaoAntiga.get(j.nickname) - j.pontuacao;
            pontosPerdidosRodada.put(j.nickname, perdidos);
        }


        List<String> nicknamesEliminados = new ArrayList<>();
        for (Jogador jogador : jogadoresAtivosNaRodada) {
            int perdidos = pontosPerdidosRodada.getOrDefault(jogador.nickname, 0);
            if (perdidos > 0) {
                try {
                    jogador.enviarMensagem(serverSocket, "Você perdeu " + perdidos + " ponto(s) nesta partida.");
                } catch (IOException e) { System.err.println("Erro ao enviar pontos perdidos: " + e.getMessage()); }
            } else if (numJogadoresComNumero > 0) {
                try {
                    jogador.enviarMensagem(serverSocket, "Você não perdeu pontos nesta partida.");
                } catch (IOException e) { System.err.println("Erro ao enviar não perdeu pontos: " + e.getMessage()); }
            }

            try {
                jogador.enviarMensagem(serverSocket, "Seu placar atual é: " + jogador.pontuacao);
            } catch (IOException e) { System.err.println("Erro ao enviar placar: " + e.getMessage()); }
            System.out.println("DEBUG: Placar de " + jogador.nickname + ": " + jogador.pontuacao);

            if (jogador.pontuacao <= -6) {
                try {
                    jogador.enviarMensagem(serverSocket, "Você foi eliminado(a)!");
                } catch (IOException e) { System.err.println("Erro ao enviar mensagem de eliminação: " + e.getMessage()); }
                nicknamesEliminados.add(jogador.nickname);
                System.out.println("DEBUG: Jogador(a) " + jogador.nickname + " foi eliminado(a).");
            }
            jogador.valorEscolhido = -1;
        }
        
        // Mensagem de fim de rodada para todos os jogadores ativos
        for (Jogador jogador : jogadoresAtivosNaRodada) {
             try {
                 jogador.enviarMensagem(serverSocket, "------------------------------------\nFim da Rodada. Aguardando próxima jogada...");
             } catch (IOException e) { System.err.println("Erro ao enviar fim de rodada: " + e.getMessage()); }
        }


        for (String nickname : nicknamesEliminados) {
            synchronized (jogadoresConectados) {
                if (jogadoresConectados.get(nickname) != null && jogadoresConectados.get(nickname).emJogo) {
                    semaforoPartida.release();
                }
                jogadoresConectados.remove(nickname);
            }
        }

        long jogadoresAtualmenteEmJogo = jogadoresConectados.values().stream().filter(j -> j.emJogo).count();

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
                try {
                    vencedor.enviarMensagem(serverSocket, "Parabéns! Você foi o(a) vencedor(a)!");
                } catch (IOException e) { System.err.println("Erro ao enviar mensagem de vitória: " + e.getMessage()); }
                System.out.println("DEBUG: Jogador(a) " + vencedor.nickname + " venceu o jogo!");
            }

            synchronized (jogadoresConectados) {
                jogadoresConectados.clear();
            }
            semaforoPartida = new Semaphore(N_JOGADORES_INICIAIS);
            barreiraRodada = null;
            estadoAtualJogo = EstadoGlobalJogo.FIM_DE_PARTIDA;
            System.out.println("DEBUG: Fim de jogo. Servidor pronto para nova partida.");
        } else if (jogadoresAtualmenteEmJogo == 0 && !jogadoresConectados.isEmpty()) {
            System.out.println("DEBUG: Todos os jogadores que estavam em jogo foram eliminados. Fim da partida.");
            semaforoPartida = new Semaphore(N_JOGADORES_INICIAIS);
            barreiraRodada = null;
            estadoAtualJogo = EstadoGlobalJogo.FIM_DE_PARTIDA;
            synchronized (jogadoresConectados) {
                for (Jogador j : jogadoresConectados.values()) {
                    j.emJogo = false;
                    j.pontuacao = 0;
                    try {
                        j.enviarMensagem(serverSocket, "A partida atual foi encerrada. Digite 2 para iniciar uma nova partida.");
                    } catch (IOException e) { System.err.println("Erro ao enviar mensagem de partida encerrada: " + e.getMessage()); }
                }
            }
            System.out.println("DEBUG: Servidor pronto para nova partida com jogadores existentes.");
        }
        else if (jogadoresConectados.isEmpty()) {
            System.out.println("DEBUG: Todos os jogadores foram eliminados ou saíram. Fim de jogo.");
            semaforoPartida = new Semaphore(N_JOGADORES_INICIAIS);
            barreiraRodada = null;
            estadoAtualJogo = EstadoGlobalJogo.FIM_DE_PARTIDA;
            System.out.println("DEBUG: Fim de jogo. Servidor pronto para nova partida.");
        }
         else {
            estadoAtualJogo = EstadoGlobalJogo.EM_ANDAMENTO;
            configurarBarreira();
            synchronized (jogadoresConectados) {
                for (Jogador jogador : jogadoresConectados.values()) {
                    if (jogador.emJogo) {
                        try {
                            jogador.enviarMensagem(serverSocket, "Escolha um número entre 0 e 100:");
                        } catch (IOException e) { System.err.println("Erro ao enviar pedido de escolha de número: " + e.getMessage()); }
                    }
                }
            }
        }
    }
}