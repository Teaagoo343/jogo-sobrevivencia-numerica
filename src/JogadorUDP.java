package src;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Scanner;

public class JogadorUDP {

    private static final int PORTA_SERVIDOR = 3000;
    private static final String ENDERECO_SERVIDOR = "localhost";

    public static void main(String[] args) {
        DatagramSocket clientSocket = null;
        Scanner scanner = new Scanner(System.in);

        try {
            clientSocket = new DatagramSocket();
            // REMOVIDO: clientSocket.setSoTimeout(5000); para evitar a mensagem de timeout.
            // O cliente agora vai esperar bloqueando por mensagens do servidor.

            InetAddress IPAddress = InetAddress.getByName(ENDERECO_SERVIDOR);

            System.out.println("Jogo da Sobrevivência Numérica");
            System.out.print("Digite seu nickname: ");
            String nickname = scanner.nextLine();

            enviarMensagem(clientSocket, IPAddress, PORTA_SERVIDOR, nickname);
            System.out.println("Enviando seu cadastro para o servidor do jogo...");

            boolean jogoAtivo = true;
            String ultimaMensagemServidor = "";

            while (jogoAtivo) {
                String mensagemRecebida = null;
                try {
                    mensagemRecebida = receberMensagem(clientSocket);
                    // Não imprime a mensagem "Nenhuma mensagem..."
                    System.out.println(mensagemRecebida);
                    ultimaMensagemServidor = mensagemRecebida;

                    if (ultimaMensagemServidor.contains("Você escolheu sair do jogo. Até mais!") ||
                        ultimaMensagemServidor.contains("Você foi eliminado(a)!") ||
                        ultimaMensagemServidor.contains("Parabéns! Você foi o(a) vencedor(a)!")) {
                        jogoAtivo = false;
                        break;
                    }
                    if (ultimaMensagemServidor.contains("Desculpe, a entrada para cadastro é inválida") ||
                        ultimaMensagemServidor.contains("Desculpe, este nickname já está em uso.")) {
                        System.out.println("Erro no cadastro. Por favor, reinicie o cliente e tente outro nickname ou verifique a entrada.");
                        jogoAtivo = false;
                        break;
                    }

                } catch (SocketException e) {
                    // Se houver uma exceção, não imprime o "timed out"
                    System.err.println("Conexão com o servidor perdida: " + e.getMessage());
                    jogoAtivo = false;
                    break;
                } catch (IOException e) {
                    System.err.println("Erro ao receber mensagem do servidor: " + e.getMessage());
                }

                if (jogoAtivo && mensagemRecebida != null) {
                    // Adicionada a lógica para pedir entrada após as mensagens específicas do servidor
                    if (ultimaMensagemServidor.contains("O que deseja:") || 
                        ultimaMensagemServidor.contains("Escolha um número entre 0 e 100:")) { 
                        
                        System.out.print("Sua escolha: ");
                        String escolhaUsuario = scanner.nextLine();
                        enviarMensagem(clientSocket, IPAddress, PORTA_SERVIDOR, escolhaUsuario);
                    }
                    // Adicionando um "enter" visual após o placar ou fim de rodada, se a mensagem indicar isso.
                    // Isso é uma heurística, o ideal seria o servidor enviar um comando específico.
                    if (ultimaMensagemServidor.contains("Seu placar é:") || ultimaMensagemServidor.contains("Fim da Rodada.")) {
                         System.out.println("\n------------------------------------\n"); // Separador visual
                    }
                }
            }

        } catch (SocketException e) {
            System.err.println("Erro ao criar o socket do cliente: " + e.getMessage());
        } catch (UnknownHostException e) {
            System.err.println("Endereço do servidor desconhecido: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("Erro de I/O geral no cliente: " + e.getMessage());
        } finally {
            if (clientSocket != null && !clientSocket.isClosed()) {
                clientSocket.close();
                System.out.println("Cliente encerrado.");
            }
            if (scanner != null) {
                scanner.close();
            }
        }
    }

    private static void enviarMensagem(DatagramSocket socket, InetAddress enderecoDestino, int portaDestino, String mensagem) throws IOException {
        byte[] dados = mensagem.getBytes();
        DatagramPacket pacote = new DatagramPacket(dados, dados.length, enderecoDestino, portaDestino);
        socket.send(pacote);
    }

    private static String receberMensagem(DatagramSocket socket) throws IOException {
        byte[] bufferRecebimento = new byte[1024];
        DatagramPacket pacoteRecebido = new DatagramPacket(bufferRecebimento, bufferRecebimento.length);
        socket.receive(pacoteRecebido);
        return new String(pacoteRecebido.getData(), 0, pacoteRecebido.getLength()).trim();
    }
}