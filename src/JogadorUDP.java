package src; // Certifique-se de que o pacote é o mesmo do servidor

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Scanner;

public class JogadorUDP {

    private static final int PORTA_SERVIDOR = 3000; // A porta que o servidor JuizUDP está escutando
    private static final String ENDERECO_SERVIDOR = "localhost"; // Onde o servidor JuizUDP está rodando

    public static void main(String[] args) {
        DatagramSocket clientSocket = null; // Nosso "correio" UDP para o cliente
        Scanner scanner = new Scanner(System.in); // Para ler a entrada do teclado do usuário

        try {
            // O cliente abre um socket UDP em uma porta aleatória (o sistema operacional escolhe).
            clientSocket = new DatagramSocket();
            // Obtém o endereço IP do servidor.
            InetAddress IPAddress = InetAddress.getByName(ENDERECO_SERVIDOR);

            // --- Etapa de Cadastro do Nickname ---
            System.out.println("Jogo da Sobrevivência Numérica");
            System.out.print("Digite seu nickname: ");
            String nickname = scanner.nextLine();

            // Envia o nickname para o servidor como a primeira mensagem
            enviarMensagem(clientSocket, IPAddress, PORTA_SERVIDOR, nickname);
            System.out.println("Enviando seu cadastro para o servidor do jogo...");

            // --- Loop Principal do Jogo ---
            // O cliente precisa estar sempre pronto para receber mensagens do servidor
            // e também para enviar as escolhas do usuário.
            // Para simplificar, vamos ter um loop que alterna entre receber e enviar,
            // mas em um jogo real, você teria threads separadas para isso.

            boolean jogoAtivo = true;
            String ultimaMensagemServidor = ""; // Guarda a última mensagem do servidor

            while (jogoAtivo) {
                // PRIMEIRO: Tenta receber uma mensagem do servidor
                try {
                    String mensagemRecebida = receberMensagem(clientSocket);
                    System.out.println(mensagemRecebida); // Imprime a mensagem do servidor
                    ultimaMensagemServidor = mensagemRecebida; // Guarda a última mensagem

                    // Lógica para sair do loop se o jogo terminar ou o jogador for eliminado/vencer
                    if (mensagemRecebida.contains("Você escolheu sair do jogo. Até mais!") ||
                        mensagemRecebida.contains("Você foi eliminado(a)!") ||
                        mensagemRecebida.contains("Parabéns! Você foi o(a) vencedor(a)!")) {
                        jogoAtivo = false;
                        break; // Sai do loop imediatamente
                    }

                } catch (SocketException e) {
                    // Isso pode acontecer se o socket for fechado por outro thread ou erro grave
                    System.err.println("Conexão com o servidor perdida: " + e.getMessage());
                    jogoAtivo = false;
                    break;
                } catch (IOException e) {
                    // Erro de I/O, pode ser um pacote mal formado ou problema de rede
                    System.err.println("Erro ao receber mensagem do servidor: " + e.getMessage());
                    // Não encerra o jogo, apenas informa o erro e continua
                }

                // SEGUNDO: Envia a escolha do usuário
                // Só pede entrada se o servidor está pedindo uma ação ou não enviou mensagem de encerramento
                if (jogoAtivo && (ultimaMensagemServidor.contains("O que deseja:") || ultimaMensagemServidor.contains("Escolha um número entre 0 e 100:"))) {
                    System.out.print("Sua escolha: ");
                    String escolhaUsuario = scanner.nextLine();
                    enviarMensagem(clientSocket, IPAddress, PORTA_SERVIDOR, escolhaUsuario);
                }
            }

        } catch (SocketException e) {
            System.err.println("Erro ao criar o socket do cliente: " + e.getMessage());
        } catch (UnknownHostException e) {
            System.err.println("Endereço do servidor desconhecido: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("Erro de I/O geral no cliente: " + e.getMessage());
        } finally {
            // Garante que o socket do cliente seja fechado.
            if (clientSocket != null && !clientSocket.isClosed()) {
                clientSocket.close();
                System.out.println("Cliente encerrado.");
            }
            if (scanner != null) {
                scanner.close();
            }
        }
    }

    // Método auxiliar para enviar uma mensagem via UDP.
    private static void enviarMensagem(DatagramSocket socket, InetAddress enderecoDestino, int portaDestino, String mensagem) throws IOException {
        byte[] dados = mensagem.getBytes(); // Converte a mensagem para bytes
        // Cria um DatagramPacket com os dados, tamanho, endereço e porta de destino
        DatagramPacket pacote = new DatagramPacket(dados, dados.length, enderecoDestino, portaDestino);
        socket.send(pacote); // Envia o pacote
    }

    // Método auxiliar para receber uma mensagem via UDP.
    private static String receberMensagem(DatagramSocket socket) throws IOException {
        byte[] bufferRecebimento = new byte[1024]; // Buffer para os dados recebidos
        DatagramPacket pacoteRecebido = new DatagramPacket(bufferRecebimento, bufferRecebimento.length);
        socket.receive(pacoteRecebido); // Bloqueia até receber um pacote
        return new String(pacoteRecebido.getData(), 0, pacoteRecebido.getLength()).trim(); // Converte e retorna
    }
}