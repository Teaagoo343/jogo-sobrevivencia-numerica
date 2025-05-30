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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;
import java.util.Comparator; // Para ordenar a lista de jogadores

// Classe que representa um Jogador no SERVIDOR (Juiz)
// Teremos que criar essa classe, mas por enquanto, vamos usar uma versão simples
class Jogador {
    String nickname;
    InetAddress ip;
    int porta;
    int pontuacao = 0; // Começa com 0 pontos
    int valorEscolhido = -1; // Número que o jogador escolhe em uma rodada

    public Jogador(String nickname, InetAddress ip, int porta) {
        this.nickname = nickname;
        this.ip = ip;
        this.porta = porta;
        this.pontuacao = 0; // Inicializa pontuação em 0
    }

    // Métodos para enviar mensagens de volta para este jogador
    public void enviarMensagem(DatagramSocket serverSocket, String mensagem) throws IOException {
        byte[] dados = mensagem.getBytes();
        DatagramPacket pacoteResposta = new DatagramPacket(dados, dados.length, this.ip, this.porta);
        serverSocket.send(pacoteResposta);
    }
}


public class JuizUDP {

    // Lista de jogadores conectados. Usaremos um HashMap para facilitar o acesso pelo nickname ou IP/Porta
    // A chave pode ser uma combinação de IP + Porta para identificar unicamente o jogador
    // Ou, para simplificar por enquanto, o nickname, mas teremos que gerenciar nicknames duplicados.
    // Vamos usar o nickname como chave para facilitar o acesso no momento.
    private static Map<String, Jogador> jogadoresConectados = new HashMap<>();

    // A porta que o servidor UDP vai escutar
    private static final int PORTA_SERVIDOR = 3000; // Conforme os PDFs 

    // Variáveis para a lógica do jogo (adaptadas do código TCP)
    public static int N_JOGADORES_INICIAIS = 3; // O jogo começa com 3 jogadores 
    public static Semaphore semaforoCadastro = new Semaphore(N_JOGADORES_INICIAIS); // Para limitar o número de jogadores
    public static CyclicBarrier barreiraRodada; // Para sincronizar os jogadores em cada rodada

    // Método para inicializar/reconfigurar a barreira
    public static void configurarBarreira() {
        // A ação a ser executada quando a barreira é quebrada (todos os jogadores jogaram)
        Runnable acaoBarreira = () -> {
            System.out.println("DEBUG: Barreira quebrada. Processando rodada...");
            try {
                processarRodada();
                // A barreira será configurada novamente após a rodada, se necessário.
            } catch (IOException e) {
                System.err.println("Erro ao processar rodada após barreira: " + e.getMessage());
            }
        };
        // O número de partes para a barreira é o número de jogadores ativos
        barreiraRodada = new CyclicBarrier(jogadoresConectados.size(), acaoBarreira);
        System.out.println("DEBUG: Barreira configurada para " + jogadoresConectados.size() + " jogadores.");
    }


    public static void main(String[] args) {
        DatagramSocket serverSocket = null; // Nosso "correio" UDP para o servidor

        try {
            serverSocket = new DatagramSocket(PORTA_SERVIDOR); // Abre a caixa de correio na porta 3000
            System.out.println("Servidor do Jogo da Sobrevivência Numérica iniciado na porta " + PORTA_SERVIDOR);
            System.out.println("Aguardando jogadores...");

            byte[] bufferRecebimento = new byte[1024]; // Buffer para armazenar os dados recebidos

            while (true) { // Loop infinito para o servidor ficar escutando
                DatagramPacket pacoteRecebido = new DatagramPacket(bufferRecebimento, bufferRecebimento.length);
                serverSocket.receive(pacoteRecebido); // Servidor espera por um "cartão postal" 

                // Extrai as informações do remetente
                InetAddress enderecoCliente = pacoteRecebido.getAddress();
                int portaCliente = pacoteRecebido.getPort();
                String mensagemRecebida = new String(pacoteRecebido.getData(), 0, pacoteRecebido.getLength()).trim();

                System.out.println("DEBUG: Mensagem recebida de " + enderecoCliente + ":" + portaCliente + " -> " + mensagemRecebida);

                // Vamos processar a mensagem aqui.
                // Por enquanto, apenas um eco simples de volta para o cliente.
                // Depois, adicionaremos a lógica do jogo.

                // A lógica aqui será mais complexa, tratando o estado do jogo e dos jogadores.
                // Por exemplo, a primeira mensagem pode ser um "cadastro de nickname"
                // ou uma escolha de menu (1, 2, 3).

                // Se for uma mensagem de cadastro de nickname
                if (!jogadoresConectados.containsKey(mensagemRecebida) && jogadoresConectados.size() < N_JOGADORES_INICIAIS) {
                    try {
                        semaforoCadastro.acquire(); // Tenta pegar uma "vaga" para o jogador
                        Jogador novoJogador = new Jogador(mensagemRecebida, enderecoCliente, portaCliente);
                        jogadoresConectados.put(novoJogador.nickname, novoJogador); // Adiciona o jogador à lista
                        System.out.println("Jogador(a): " + novoJogador.nickname + " entrou no jogo. IP:" + novoJogador.ip + " Porta: " + novoJogador.porta);

                        // Envia mensagem de boas-vindas e menu
                        String boasVindas = "Bem-vindo(a), " + novoJogador.nickname + ".\n" +
                                            "Digite 1 - para ver as regras do jogo.\n" +
                                            "Digite 2 - para iniciar o jogo.\n" +
                                            "Digite 3 - para sair do jogo.\n" +
                                            "O que deseja:";
                        novoJogador.enviarMensagem(serverSocket, boasVindas);

                        // Se atingimos o número de jogadores para iniciar o jogo
                        if (jogadoresConectados.size() == N_JOGADORES_INICIAIS) {
                            System.out.println("DEBUG: " + N_JOGADORES_INICIAIS + " jogadores conectados. Iniciando a barreira...");
                            configurarBarreira(); // Configura a barreira para a rodada inicial
                            // Envia "Que comecem os jogos..." para todos os jogadores
                            for (Jogador j : jogadoresConectados.values()) {
                                j.enviarMensagem(serverSocket, "Jogadores oponentes encontrados. Que comecem os jogos...");
                            }
                        } else {
                            // Envia status de espera para o novo jogador
                             novoJogador.enviarMensagem(serverSocket, "Aguardando jogadores oponentes... (" + jogadoresConectados.size() + "/" + N_JOGADORES_INICIAIS + " conectados)");
                        }

                    } catch (InterruptedException e) {
                        System.err.println("Erro ao adquirir semáforo: " + e.getMessage());
                        semaforoCadastro.release(); // Libera se algo der errado no acquire
                    }
                } else {
                    // Se não for um novo cadastro, é uma interação de um jogador existente
                    Jogador jogadorAtual = jogadoresConectados.get(enderecoCliente.getHostAddress() + ":" + portaCliente);
                    if (jogadorAtual == null) {
                         // Tenta encontrar pelo nickname se não encontrar pelo IP/Porta (menos ideal para UDP, mas pode ocorrer no fluxo inicial)
                         // Para este exemplo, vamos considerar que o nickname é único para simplificar a busca
                         // Em um sistema real, seria melhor usar IP+Porta como chave ou um ID único do jogador
                         // Para os testes iniciais, o nickname servirá.
                        boolean found = false;
                        for (Jogador j : jogadoresConectados.values()) {
                            if (j.ip.equals(enderecoCliente) && j.porta == portaCliente) {
                                jogadorAtual = j;
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            System.out.println("DEBUG: Mensagem de um cliente desconhecido: " + mensagemRecebida);
                            String resposta = "Desculpe, seu nickname não foi reconhecido ou o jogo já começou.";
                            byte[] dadosResposta = resposta.getBytes();
                            DatagramPacket pacoteResposta = new DatagramPacket(dadosResposta, dadosResposta.length, enderecoCliente, portaCliente);
                            serverSocket.send(pacoteResposta);
                            continue; // Ignora o resto do loop para esta mensagem
                        }
                    }

                    // Processar as escolhas do menu (1, 2, 3) ou o número do jogo
                    try {
                        int escolha = Integer.parseInt(mensagemRecebida);
                        switch (escolha) {
                            case 1: // Ver regras
                                // ... código das regras ...
                                // Após mostrar as regras, reenvia o menu
                                String menuMessage = "Bem-vindo(a)," + jogadorAtual.nickname + ".\n" +
                                                     "Digite 1 - para ver as regras do jogo.\n" +
                                                     "Digite 2 - para iniciar o jogo.\n" +
                                                     "Digite 3 - para sair do jogo.\n" +
                                                     "O que deseja:";
                                jogadorAtual.enviarMensagem(serverSocket, menuMessage);
                                break;
                            // ... outros cases ...
                            default: // Tenta interpretar como um número para o jogo
                                // ... código ...
                                // Reenvia o menu se a entrada for inválida e não for um número de jogo
                                // REMOVA 'String' AQUI
                                menuMessage = "Bem-vindo(a)," + jogadorAtual.nickname + ".\n" + // AQUI não tem mais 'String'
                                             "Digite 1 - para ver as regras do jogo.\n" +
                                             "Digite 2 - para iniciar o jogo.\n" +
                                             "Digite 3 - para sair do jogo.\n" +
                                             "O que deseja:";
                                jogadorAtual.enviarMensagem(serverSocket, menuMessage);
                                break;
                            default: // Tenta interpretar como um número para o jogo
                                if (jogadoresConectados.size() >= N_JOGADORES_INICIAIS && escolha >= 0 && escolha <= 100) {
                                    jogadorAtual.valorEscolhido = escolha;
                                    System.out.println("Jogador(a) " + jogadorAtual.nickname + " escolheu o número: " + jogadorAtual.valorEscolhido);
                                    jogadorAtual.enviarMensagem(serverSocket, "Você escolheu o número: " + escolha + ".\nEnviando o número escolhido para o servidor do jogo...\nAguardando os outros jogadores..."); 

                                    // Tenta alcançar a barreira. Se todos chegarem, a barreira é quebrada e a rodada processada.
                                    // Importante: A barreira precisa ser resetada ou configurada para cada rodada
                                    try {
                                        barreiraRodada.await(); // Espera todos os jogadores chegarem aqui
                                    } catch (Exception e) { // BrokenBarrierException, InterruptedException
                                        System.err.println("DEBUG: Erro na barreira ou barreira quebrada antes da hora: " + e.getMessage());
                                        // O servidor pode enviar uma mensagem de erro ou tentar reiniciar a rodada
                                        jogadorAtual.enviarMensagem(serverSocket, "Ocorreu um erro na rodada. Tente novamente.");
                                    }

                                } else {
                                    jogadorAtual.enviarMensagem(serverSocket, "Entrada inválida ou jogo não iniciado. Digite um número de 0 a 100 ou escolha uma opção do menu.");
                                    // Reenvia o menu se a entrada for inválida e não for um número de jogo
                                    String menuMessage = "Bem-vindo(a)," + jogadorAtual.nickname + ".\n" +
                                                         "Digite 1 - para ver as regras do jogo.\n" +
                                                         "Digite 2 - para iniciar o jogo.\n" +
                                                         "Digite 3 - para sair do jogo.\n" +
                                                         "O que deseja:"; 
                                    jogadorAtual.enviarMensagem(serverSocket, menuMessage);
                                }
                                break;
                        }
                    } catch (NumberFormatException e) {
                        jogadorAtual.enviarMensagem(serverSocket, "Entrada inválida. Digite um número para a opção do menu ou para sua jogada.");
                        // Reenvia o menu se a entrada não for um número
                        String menuMessage = "Bem-vindo(a)," + jogadorAtual.nickname + ".\n" +
                                             "Digite 1 - para ver as regras do jogo.\n" +
                                             "Digite 2 - para iniciar o jogo.\n" +
                                             "Digite 3 - para sair do jogo.\n" +
                                             "O que deseja:";
                        jogadorAtual.enviarMensagem(serverSocket, menuMessage);
                    }
                }
            }

        } catch (SocketException e) {
            System.err.println("Erro ao criar/usar o socket do servidor: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("Erro de I/O no servidor: " + e.getMessage());
        } finally {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        }
    }

    // Adaptação do processarRodada do código TCP
    public static synchronized void processarRodada() throws IOException {
        System.out.println("DEBUG: Iniciando processamento da rodada.");

        // Verificar se ainda há jogadores ativos (evita erro se um jogador saiu logo após a barreira)
        if (jogadoresConectados.isEmpty()) {
            System.out.println("DEBUG: Não há jogadores para processar a rodada.");
            return;
        }

        // Criar uma lista temporária dos jogadores ativos para a rodada
        List<Jogador> jogadoresAtivos = new ArrayList<>(jogadoresConectados.values());

        // Se houver menos de 3 jogadores e a barreira for quebrada, é um problema de sincronização
        if (jogadoresAtivos.size() < 2) { // Precisa de pelo menos 2 para as regras de 2 jogadores
             System.out.println("DEBUG: Número insuficiente de jogadores para continuar o jogo. Jogo encerrado.");
             for(Jogador j : jogadoresAtivos){
                 j.enviarMensagem(barreiraRodada.getParties().get(0).toString(), "Jogo encerrado devido a falta de jogadores."); // TODO: Adaptar para usar o socket
             }
             // Resetar o estado do jogo para nova partida
             jogadoresConectados.clear();
             semaforoCadastro = new Semaphore(N_JOGADORES_INICIAIS);
             barreiraRodada = null;
             return;
        }


        double soma = 0;
        // Calcula a soma dos valores escolhidos pelos jogadores ativos na rodada
        for (Jogador jogador : jogadoresAtivos) {
            if (jogador.valorEscolhido != -1) { // Só soma se o jogador realmente escolheu um número
                soma += jogador.valorEscolhido;
            }
        }

        // Evita divisão por zero se por algum motivo nenhum jogador escolheu um número válido
        if (jogadoresAtivos.stream().noneMatch(j -> j.valorEscolhido != -1)) {
            System.out.println("DEBUG: Nenhum jogador escolheu um número válido nesta rodada. Pulando cálculo.");
             for (Jogador jogador : jogadoresAtivos) {
                jogador.enviarMensagem(barreiraRodada.getParties().get(0).toString(), "Nenhum número válido escolhido na rodada. Placar permanece o mesmo."); // TODO: Adaptar para usar o socket
                jogador.enviarMensagem(barreiraRodada.getParties().get(0).toString(), "Seu placar é: " + jogador.pontuacao); // TODO: Adaptar para usar o socket
            }
            // Resetar valores escolhidos para próxima rodada e configurar barreira novamente
            for (Jogador jogador : jogadoresAtivos) {
                jogador.valorEscolhido = -1;
            }
            configurarBarreira();
            return;
        }

        double media = soma / jogadoresAtivos.stream().filter(j -> j.valorEscolhido != -1).count();
        double valorAlvo = media * 0.8;

        System.out.println("DEBUG: Média: " + media + ", Valor Alvo: " + valorAlvo);

        // Ordena os jogadores pela distância ao valor alvo (do mais próximo ao mais distante) 
        jogadoresAtivos.sort(Comparator.comparingDouble(j -> Math.abs(j.valorEscolhido - valorAlvo)));

        // Aplica a pontuação
        // As regras mudam se restarem apenas 2 jogadores
        if (jogadoresAtivos.size() == 3) {
            // O mais próximo não perde pontos  (já está na primeira posição por causa da ordenação)
            jogadoresAtivos.get(0).pontuacao += 0; // Para clareza, não muda
            // O do meio perde 1 ponto 
            jogadoresAtivos.get(1).pontuacao--;
            // O mais distante perde 2 pontos 
            jogadoresAtivos.get(2).pontuacao -= 2;
        } else if (jogadoresAtivos.size() == 2) {
            // O mais próximo não perde pontos 
            jogadoresAtivos.get(0).pontuacao += 0; // Para clareza, não muda
            // O mais distante perde 1 ponto 
            jogadoresAtivos.get(1).pontuacao--;
        }

        // Envia os placares atualizados para todos os jogadores ativos e verifica eliminações
        List<String> jogadoresEliminados = new ArrayList<>();
        for (Jogador jogador : jogadoresAtivos) {
            jogador.enviarMensagem(barreiraRodada.getParties().get(0).toString(), "Seu placar é: " + jogador.pontuacao); // TODO: Adaptar para usar o socket
            System.out.println("DEBUG: Placar de " + jogador.nickname + ": " + jogador.pontuacao);

            // Verifica se o jogador foi eliminado 
            if (jogador.pontuacao <= -6) {
                jogador.enviarMensagem(barreiraRodada.getParties().get(0).toString(), "Você foi eliminado(a)!"); // TODO: Adaptar para usar o socket 
                jogadoresEliminados.add(jogador.nickname);
                System.out.println("DEBUG: Jogador(a) " + jogador.nickname + " foi eliminado.");
            }
            // Resetar o valor escolhido para a próxima rodada
            jogador.valorEscolhido = -1;
        }

        // Remove os jogadores eliminados do mapa principal
        for (String nickname : jogadoresEliminados) {
            jogadoresConectados.remove(nickname);
            semaforoCadastro.release(); // Libera uma vaga
        }

        // Verifica se há um vencedor 
        if (jogadoresConectados.size() == 1) {
            Jogador vencedor = jogadoresConectados.values().iterator().next();
            vencedor.enviarMensagem(barreiraRodada.getParties().get(0).toString(), "Parabéns! Você foi o(a) vencedor(a)!"); // TODO: Adaptar para usar o socket 
            System.out.println("DEBUG: Jogador(a) " + vencedor.nickname + " venceu o jogo!");

            // Limpa todos os jogadores e reinicia o semáforo para uma nova partida
            jogadoresConectados.clear();
            semaforoCadastro = new Semaphore(N_JOGADORES_INICIAIS);
            barreiraRodada = null; // A barreira será configurada novamente quando 3 jogadores se conectarem
            System.out.println("DEBUG: Fim de jogo. Servidor pronto para nova partida.");
        } else if (jogadoresConectados.size() == 0) {
            System.out.println("DEBUG: Todos os jogadores foram eliminados. Fim de jogo.");
            semaforoCadastro = new Semaphore(N_JOGADORES_INICIAIS);
            barreiraRodada = null;
            System.out.println("DEBUG: Fim de jogo. Servidor pronto para nova partida.");
        }
         else {
            // Se o jogo continua, configura a barreira para a próxima rodada com os jogadores restantes
            configurarBarreira();
            // Envia mensagem para os jogadores restantes escolherem um novo número
            for (Jogador jogador : jogadoresConectados.values()) {
                jogador.enviarMensagem(barreiraRodada.getParties().get(0).toString(), "Escolha um número entre 0 e 100:"); // TODO: Adaptar para usar o socket 
            }
        }
    }
}