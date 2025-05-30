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
import java.util.concurrent.BrokenBarrierException; // Import necessário para tratar a barreira
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;
import java.util.Comparator; // Para ordenar a lista de jogadores

// Classe que representa um Jogador no SERVIDOR (Juiz)
// O servidor mantém uma instância desta classe para cada jogador conectado,
// armazenando seu estado (nickname, IP, porta, pontuação, número escolhido na rodada).
class Jogador {
    String nickname;      // Nome do jogador
    InetAddress ip;       // Endereço IP do jogador
    int porta;            // Porta UDP do jogador
    int pontuacao = 0;    // Pontuação atual do jogador (começa em 0)
    int valorEscolhido = -1; // Número que o jogador escolhe em uma rodada (-1 indica que ainda não escolheu)

    public Jogador(String nickname, InetAddress ip, int porta) {
        this.nickname = nickname;
        this.ip = ip;
        this.porta = porta;
        this.pontuacao = 0; // Inicializa pontuação em 0
    }

    // Método para enviar uma mensagem de volta para este jogador específico.
    // Recebe o DatagramSocket do servidor para poder enviar o pacote.
    public void enviarMensagem(DatagramSocket serverSocket, String mensagem) throws IOException {
        byte[] dados = mensagem.getBytes(); // Converte a mensagem de String para bytes
        // Cria um DatagramPacket com os dados, tamanho, endereço IP e porta do jogador
        DatagramPacket pacoteResposta = new DatagramPacket(dados, dados.length, this.ip, this.porta);
        serverSocket.send(pacoteResposta); // Envia o pacote UDP
    }
}


public class JuizUDP {

    // Mapa que armazena todos os jogadores conectados. A chave é o nickname do jogador.
    // Usamos static para que seja acessível de qualquer lugar na classe e persista durante a execução do servidor.
    private static Map<String, Jogador> jogadoresConectados = new HashMap<>();

    // A porta que o servidor UDP vai escutar por mensagens dos clientes.
    private static final int PORTA_SERVIDOR = 3000; // Conforme os PDFs e exemplos.

    // Número de jogadores necessários para o jogo começar e para as rodadas terem as regras iniciais.
    public static int N_JOGADORES_INICIAIS = 3;

    // Semáforo para controlar o número de jogadores que podem se cadastrar.
    // Garante que apenas N_JOGADORES_INICIAIS jogadores possam iniciar uma partida.
    public static Semaphore semaforoCadastro = new Semaphore(N_JOGADORES_INICIAIS);

    // Barreira para sincronizar os jogadores a cada rodada.
    // Quando todos os jogadores de uma rodada "chegam" na barreira, a ação definida é executada.
    public static CyclicBarrier barreiraRodada;

    // Referência ao socket do servidor, para que possa ser usada na ação da barreira e em processarRodada.
    // Torna-o static para ser acessível de dentro do Runnable da barreira.
    private static DatagramSocket servidorSocketGlobal;


    // Método para inicializar ou reconfigurar a barreira de sincronização das rodadas.
    public static void configurarBarreira() {
        // Ação que será executada quando todos os jogadores chegarem à barreira.
        Runnable acaoBarreira = () -> {
            System.out.println("DEBUG: Barreira quebrada. Processando rodada...");
            try {
                // Chama o método que processa a lógica da rodada, passando o socket do servidor.
                // O servidorSocketGlobal foi criado para ser acessível aqui.
                processarRodada(servidorSocketGlobal);
            } catch (IOException e) {
                System.err.println("Erro ao processar rodada após barreira: " + e.getMessage());
            }
        };

        // Verifica se há jogadores ativos antes de configurar a barreira.
        // A barreira precisa de pelo menos 1 parte para ser criada, mas para o jogo, precisamos de 2 ou 3.
        if (jogadoresConectados.size() > 0) {
            barreiraRodada = new CyclicBarrier(jogadoresConectados.size(), acaoBarreira);
            System.out.println("DEBUG: Barreira configurada para " + jogadoresConectados.size() + " jogadores.");
        } else {
            // Se não há jogadores, a barreira não é necessária e é setada para null.
            barreiraRodada = null;
            System.out.println("DEBUG: Nenhuma barreira configurada, nenhum jogador ativo.");
        }
    }


    public static void main(String[] args) {
        // O DatagramSocket principal do servidor.
        // Declarado AQUI (fora do try) e inicializado como null para ser acessível no bloco 'finally'.
        DatagramSocket serverSocket = null; 

        try {
            // Tenta abrir o socket do servidor na porta definida.
            serverSocket = new DatagramSocket(PORTA_SERVIDOR);
            // Atribui o socket local à variável global para que a ação da barreira possa acessá-lo.
            servidorSocketGlobal = serverSocket;

            System.out.println("Servidor do Jogo da Sobrevivência Numérica iniciado na porta " + PORTA_SERVIDOR);
            System.out.println("Aguardando jogadores...");

            // Buffer para armazenar os dados de cada pacote UDP recebido.
            byte[] bufferRecebimento = new byte[1024];

            // Loop infinito para o servidor continuar escutando por mensagens.
            while (true) {
                // Cria um DatagramPacket vazio para receber os dados.
                DatagramPacket pacoteRecebido = new DatagramPacket(bufferRecebimento, bufferRecebimento.length);
                // O servidor fica bloqueado aqui, esperando receber um pacote.
                serverSocket.receive(pacoteRecebido);

                // Extrai o endereço IP e a porta do remetente do pacote.
                InetAddress enderecoCliente = pacoteRecebido.getAddress();
                int portaCliente = pacoteRecebido.getPort();
                // Converte os dados do pacote para String, removendo espaços em branco extras.
                String mensagemRecebida = new String(pacoteRecebido.getData(), 0, pacoteRecebido.getLength()).trim();

                System.out.println("DEBUG: Mensagem recebida de " + enderecoCliente.getHostAddress() + ":" + portaCliente + " -> " + mensagemRecebida);

                // Variável para armazenar o jogador atual que enviou a mensagem.
                Jogador jogadorAtual = null;
                // Procura o jogador no mapa de jogadores conectados usando IP e Porta.
                // Esta é a forma mais robusta de identificar um cliente UDP.
                for (Jogador j : jogadoresConectados.values()) {
                    if (j.ip.equals(enderecoCliente) && j.porta == portaCliente) {
                        jogadorAtual = j;
                        break;
                    }
                }

                // --- Lógica para processar a mensagem recebida ---

                if (jogadorAtual == null) { // Se o jogador não foi encontrado, é um novo cliente ou um desconhecido.
                    // Tenta cadastrar um novo jogador.
                    // Condições: Não é vazio, não é nickname duplicado, e há vagas no jogo.
                    if (!mensagemRecebida.isEmpty() && !jogadoresConectados.containsKey(mensagemRecebida) && semaforoCadastro.tryAcquire()) {
                        Jogador novoJogador = new Jogador(mensagemRecebida, enderecoCliente, portaCliente);
                        jogadoresConectados.put(novoJogador.nickname, novoJogador); // Adiciona o novo jogador ao mapa
                        System.out.println("Jogador(a): " + novoJogador.nickname + " entrou no jogo. IP:" + novoJogador.ip.getHostAddress() + " Porta: " + novoJogador.porta);

                        // Envia a mensagem de boas-vindas e o menu inicial.
                        String boasVindas = "Bem-vindo(a), " + novoJogador.nickname + ".\n" +
                                            "Digite 1 - para ver as regras do jogo.\n" +
                                            "Digite 2 - para iniciar o jogo.\n" +
                                            "Digite 3 - para sair do jogo.\n" +
                                            "O que deseja:";
                        novoJogador.enviarMensagem(serverSocket, boasVindas);

                        // Se o número de jogadores conectados atingiu o limite inicial, inicia a partida.
                        if (jogadoresConectados.size() == N_JOGADORES_INICIAIS) {
                            System.out.println("DEBUG: " + N_JOGADORES_INICIAIS + " jogadores conectados. Iniciando a barreira...");
                            configurarBarreira(); // Configura a barreira para a primeira rodada.
                            // Envia mensagem de início para todos os jogadores.
                            for (Jogador j : jogadoresConectados.values()) {
                                j.enviarMensagem(serverSocket, "Jogadores oponentes encontrados. Que comecem os jogos...");
                                // Manda a primeira instrução para o jogador escolher um número.
                                j.enviarMensagem(serverSocket, "Escolha um número entre 0 e 100:");
                            }
                        } else {
                            // Se ainda não há jogadores suficientes, informa ao novo jogador.
                            novoJogador.enviarMensagem(serverSocket, "Aguardando jogadores oponentes... (" + jogadoresConectados.size() + "/" + N_JOGADORES_INICIAIS + " conectados)");
                        }
                    } else {
                        // Se não conseguiu se cadastrar (jogo cheio, nickname duplicado ou mensagem inválida para cadastro).
                        String msgCheio = "Jogo está cheio, nickname já usado ou entrada inválida. Tente novamente mais tarde.";
                        byte[] dadosMsg = msgCheio.getBytes(); // Correção: msgCheio (não msgCheho)
                        DatagramPacket pacoteErro = new DatagramPacket(dadosMsg, dadosMsg.length, enderecoCliente, portaCliente);
                        serverSocket.send(pacoteErro);
                    }
                } else { // Se a mensagem veio de um jogador já cadastrado (jogadorAtual não é null).
                    try {
                        int escolha = Integer.parseInt(mensagemRecebida); // Tenta converter a mensagem para um número.
                        String mensagemParaJogador = ""; // Variável para a mensagem de resposta ao jogador.

                        switch (escolha) {
                            case 1: // Jogador escolheu para ver as regras.
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
                                jogadorAtual.enviarMensagem(serverSocket, regras);
                                // Após mostrar as regras, reenvia o menu principal.
                                mensagemParaJogador = "Bem-vindo(a)," + jogadorAtual.nickname + ".\n" +
                                                     "Digite 1 - para ver as regras do jogo.\n" +
                                                     "Digite 2 - para iniciar o jogo.\n" +
                                                     "Digite 3 - para sair do jogo.\n" +
                                                     "O que deseja:";
                                jogadorAtual.enviarMensagem(serverSocket, mensagemParaJogador);
                                break;

                            case 2: // Jogador escolheu para iniciar o jogo ou continuar.
                                if (jogadoresConectados.size() < N_JOGADORES_INICIAIS) {
                                    mensagemParaJogador = "Aguardando mais jogadores para iniciar o jogo. (" + jogadoresConectados.size() + "/" + N_JOGADORES_INICIAIS + " conectados)";
                                    jogadorAtual.enviarMensagem(serverSocket, mensagemParaJogador);
                                } else {
                                    // Se o jogo já tem jogadores suficientes, presume que a opção 2 é para "começar a jogar" ou "escolher um número".
                                    mensagemParaJogador = "Que comecem os jogos...\nEscolha um número entre 0 e 100:";
                                    jogadorAtual.enviarMensagem(serverSocket, mensagemParaJogador);
                                }
                                break;

                            case 3: // Jogador escolheu sair do jogo.
                                jogadoresConectados.remove(jogadorAtual.nickname); // Remove o jogador do mapa.
                                semaforoCadastro.release(); // Libera uma vaga no semáforo.
                                System.out.println("Jogador(a): " + jogadorAtual.nickname + " saiu do jogo.");
                                mensagemParaJogador = "Você escolheu sair do jogo. Até mais!";
                                jogadorAtual.enviarMensagem(serverSocket, mensagemParaJogador);
                                // Se todos os jogadores saíram, o servidor se "reinicia" para uma nova partida.
                                if (jogadoresConectados.isEmpty()) {
                                    semaforoCadastro = new Semaphore(N_JOGADORES_INICIAIS); // Reinicia o semáforo.
                                    barreiraRodada = null; // Zera a barreira.
                                    System.out.println("Todos os jogadores saíram. Servidor pronto para nova partida.");
                                }
                                break;

                            default: // Para qualquer outro número, tentamos interpretar como uma jogada.
                                if (escolha >= 0 && escolha <= 100) { // Verifica se é um número entre 0 e 100.
                                    // Verifica se o jogo já tem jogadores suficientes para uma rodada.
                                    if (jogadoresConectados.size() >= N_JOGADORES_INICIAIS) {
                                        jogadorAtual.valorEscolhido = escolha; // Armazena o número escolhido pelo jogador.
                                        System.out.println("Jogador(a) " + jogadorAtual.nickname + " escolheu o número: " + jogadorAtual.valorEscolhido);
                                        mensagemParaJogador = "Você escolheu o número: " + escolha + ".\nEnviando o número escolhido para o servidor do jogo...\nAguardando os outros jogadores...";
                                        jogadorAtual.enviarMensagem(serverSocket, mensagemParaJogador);

                                        // Tenta fazer o jogador "chegar" na barreira.
                                        try {
                                            if (barreiraRodada != null) { // Garante que a barreira foi inicializada e não é null.
                                                barreiraRodada.await(); // Espera até que todos os jogadores ativos cheguem aqui.
                                            } else {
                                                // Se a barreira não foi inicializada, o jogo não está na fase de jogadas.
                                                mensagemParaJogador = "O jogo ainda não começou oficialmente. Aguarde outros jogadores.";
                                                jogadorAtual.enviarMensagem(serverSocket, mensagemParaJogador);
                                            }
                                        } catch (BrokenBarrierException | InterruptedException e) {
                                            System.err.println("DEBUG: Erro na barreira ou barreira quebrada antes da hora: " + e.getMessage());
                                            mensagemParaJogador = "Ocorreu um erro na rodada. Tente novamente.";
                                            jogadorAtual.enviarMensagem(serverSocket, mensagemParaJogador);
                                        }
                                    } else {
                                        // Não há jogadores suficientes para iniciar a rodada com jogadas.
                                        mensagemParaJogador = "Aguardando mais jogadores para iniciar o jogo. (" + jogadoresConectados.size() + "/" + N_JOGADORES_INICIAIS + " conectados)";
                                        jogadorAtual.enviarMensagem(serverSocket, mensagemParaJogador);
                                        // Reenvia o menu.
                                        mensagemParaJogador = "Bem-vindo(a)," + jogadorAtual.nickname + ".\n" +
                                                             "Digite 1 - para ver as regras do jogo.\n" +
                                                             "Digite 2 - para iniciar o jogo.\n" +
                                                             "Digite 3 - para sair do jogo.\n" +
                                                             "O que deseja:";
                                        jogadorAtual.enviarMensagem(serverSocket, mensagemParaJogador);
                                    }

                                } else { // Entrada inválida (não é uma opção de menu e nem um número de jogo válido).
                                    mensagemParaJogador = "Entrada inválida. Digite um número de 0 a 100 ou escolha uma opção do menu.";
                                    jogadorAtual.enviarMensagem(serverSocket, mensagemParaJogador);
                                    // Reenvia o menu.
                                    mensagemParaJogador = "Bem-vindo(a)," + jogadorAtual.nickname + ".\n" +
                                                         "Digite 1 - para ver as regras do jogo.\n" +
                                                         "Digite 2 - para iniciar o jogo.\n" +
                                                         "Digite 3 - para sair do jogo.\n" +
                                                         "O que deseja:";
                                    jogadorAtual.enviarMensagem(serverSocket, mensagemParaJogador);
                                }
                                break;
                        }
                    } catch (NumberFormatException e) { // Se a mensagem não puder ser convertida para número.
                        String mensagemParaJogador = "Entrada inválida. Digite um número para a opção do menu ou para sua jogada.";
                        jogadorAtual.enviarMensagem(serverSocket, mensagemParaJogador);
                        // Reenvia o menu.
                        mensagemParaJogador = "Bem-vindo(a)," + jogadorAtual.nickname + ".\n" +
                                             "Digite 1 - para ver as regras do jogo.\n" +
                                             "Digite 2 - para iniciar o jogo.\n" +
                                             "Digite 3 - para sair do jogo.\n" +
                                             "O que deseja:";
                        jogadorAtual.enviarMensagem(serverSocket, mensagemParaJogador);
                    }
                }
            }

        } catch (SocketException e) {
            System.err.println("Erro ao criar/usar o socket do servidor: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("Erro de I/O no servidor: " + e.getMessage());
        } finally {
            // Garante que o socket do servidor seja fechado quando o programa terminar ou ocorrer um erro.
            // O 'serverSocket' agora está declarado fora do try, então é acessível aqui.
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        }
    }

    // Método que contém toda a lógica para processar uma rodada do jogo.
    // Recebe o DatagramSocket do servidor para poder enviar mensagens de placar e status.
    public static synchronized void processarRodada(DatagramSocket serverSocket) throws IOException {
        System.out.println("DEBUG: Iniciando processamento da rodada.");

        // Cria uma cópia da lista de jogadores ativos para evitar ConcurrentModificationException
        // e trabalhar com uma lista estática durante o cálculo da rodada.
        List<Jogador> jogadoresAtivos = new ArrayList<>(jogadoresConectados.values());

        // Se houver menos de 2 jogadores, o jogo não pode continuar e é encerrado.
        if (jogadoresAtivos.size() < 2) {
             System.out.println("DEBUG: Número insuficiente de jogadores para continuar o jogo. Jogo encerrado.");
             for(Jogador j : jogadoresAtivos){ // Envia mensagem de encerramento para os jogadores restantes.
                 j.enviarMensagem(serverSocket, "Jogo encerrado devido a falta de jogadores.");
             }
             // Reinicia o estado do jogo para uma nova partida.
             jogadoresConectados.clear();
             semaforoCadastro = new Semaphore(N_JOGADORES_INICIAIS);
             barreiraRodada = null; // Barreira zerada, será configurada novamente na próxima partida.
             return; // Sai do método, pois o jogo terminou.
        }

        double soma = 0;
        int numJogadoresComNumero = 0; // Conta quantos jogadores realmente escolheram um número válido.
        // Calcula a soma dos valores escolhidos pelos jogadores que participaram da rodada.
        for (Jogador jogador : jogadoresAtivos) {
            if (jogador.valorEscolhido != -1) { // Só soma se o jogador realmente escolheu um número.
                soma += jogador.valorEscolhido;
                numJogadoresComNumero++;
            }
        }

        // Evita divisão por zero se, por algum motivo, nenhum jogador escolheu um número válido na rodada.
        if (numJogadoresComNumero == 0) {
            System.out.println("DEBUG: Nenhum jogador escolheu um número válido nesta rodada. Pulando cálculo.");
             for (Jogador jogador : jogadoresAtivos) {
                jogador.enviarMensagem(serverSocket, "Nenhum número válido escolhido na rodada. Placar permanece o mesmo.");
                jogador.enviarMensagem(serverSocket, "Seu placar é: " + jogador.pontuacao);
            }
            // Reseta os valores escolhidos para a próxima rodada e reconfigura a barreira.
            for (Jogador jogador : jogadoresAtivos) {
                jogador.valorEscolhido = -1;
            }
            configurarBarreira();
            return;
        }

        double media = soma / numJogadoresComNumero; // Calcula a média.
        double valorAlvo = media * 0.8;             // Calcula o valor alvo.

        System.out.println("DEBUG: Média: " + media + ", Valor Alvo: " + valorAlvo);

        // Cria uma lista apenas com os jogadores que de fato participaram desta rodada (escolheram um número).
        // Isso é importante para aplicar a pontuação corretamente.
        List<Jogador> jogadoresParaPontuar = new ArrayList<>();
        for (Jogador j : jogadoresAtivos) {
            if (j.valorEscolhido != -1) {
                jogadoresParaPontuar.add(j);
            }
        }
        // Ordena esses jogadores pela distância ao valor alvo (do mais próximo ao mais distante).
        // A função Math.abs() garante que a distância seja sempre positiva.
        jogadoresParaPontuar.sort(Comparator.comparingDouble(j -> Math.abs(j.valorEscolhido - valorAlvo)));


        // Aplica a pontuação conforme as regras do jogo.
        // As regras mudam dependendo do número de jogadores na partida.
        if (jogadoresParaPontuar.size() == 3) {
            // Com 3 jogadores:
            // O mais próximo (índice 0) não perde pontos.
            jogadoresParaPontuar.get(0).pontuacao += 0; // Para clareza, não muda
            // O do meio (índice 1) perde 1 ponto.
            jogadoresParaPontuar.get(1).pontuacao--;
            // O mais distante (índice 2) perde 2 pontos.
            jogadoresParaPontuar.get(2).pontuacao -= 2;
        } else if (jogadoresParaPontuar.size() == 2) {
            // Com 2 jogadores:
            // O mais próximo (índice 0) não perde pontos.
            jogadoresParaPontuar.get(0).pontuacao += 0; // Para clareza, não muda
            // O mais distante (índice 1) perde 1 ponto.
            jogadoresParaPontuar.get(1).pontuacao--;
        }

        // Lista para armazenar os nicknames dos jogadores que serão eliminados.
        List<String> nicknamesEliminados = new ArrayList<>();
        // Envia os placares atualizados para TODOS os jogadores que estavam ativos na rodada (mesmo os que não pontuaram).
        for (Jogador jogador : jogadoresAtivos) {
            jogador.enviarMensagem(serverSocket, "Seu placar é: " + jogador.pontuacao);
            System.out.println("DEBUG: Placar de " + jogador.nickname + ": " + jogador.pontuacao);

            // Verifica se o jogador foi eliminado (atingiu -6 pontos ou menos).
            if (jogador.pontuacao <= -6) {
                jogador.enviarMensagem(serverSocket, "Você foi eliminado(a)!");
                nicknamesEliminados.add(jogador.nickname); // Adiciona à lista de eliminados.
                System.out.println("DEBUG: Jogador(a) " + jogador.nickname + " foi eliminado.");
            }
            // Reseta o valor escolhido do jogador para a próxima rodada.
            jogador.valorEscolhido = -1;
        }

        // Remove os jogadores eliminados do mapa principal de jogadores conectados.
        for (String nickname : nicknamesEliminados) {
            jogadoresConectados.remove(nickname);
            semaforoCadastro.release(); // Libera uma vaga no semáforo para futuros jogadores.
        }

        // --- Verificação de Vencedor ou Fim de Jogo ---
        if (jogadoresConectados.size() == 1) { // Se sobrou apenas um jogador, ele é o vencedor.
            Jogador vencedor = jogadoresConectados.values().iterator().next(); // Pega o único jogador restante.
            vencedor.enviarMensagem(serverSocket, "Parabéns! Você foi o(a) vencedor(a)!");
            System.out.println("DEBUG: Jogador(a) " + vencedor.nickname + " venceu o jogo!");

            // Limpa o estado do jogo para uma nova partida.
            jogadoresConectados.clear();
            semaforoCadastro = new Semaphore(N_JOGADORES_INICIAIS); // Reinicia o semáforo.
            barreiraRodada = null; // A barreira será configurada novamente quando 3 novos jogadores se conectarem.
            System.out.println("DEBUG: Fim de jogo. Servidor pronto para nova partida.");
        } else if (jogadoresConectados.size() == 0) { // Se todos os jogadores foram eliminados.
            System.out.println("DEBUG: Todos os jogadores foram eliminados. Fim de jogo.");
            // Reinicia o estado do jogo.
            semaforoCadastro = new Semaphore(N_JOGADORES_INICIAIS);
            barreiraRodada = null;
            System.out.println("DEBUG: Fim de jogo. Servidor pronto para nova partida.");
        } else {
            // Se o jogo continua (ainda há 2 ou mais jogadores), configura a barreira para a próxima rodada.
            configurarBarreira();
            // Envia mensagem para os jogadores restantes escolherem um novo número.
            for (Jogador jogador : jogadoresConectados.values()) {
                jogador.enviarMensagem(serverSocket, "Escolha um número entre 0 e 100:");
            }
        }
    }
}