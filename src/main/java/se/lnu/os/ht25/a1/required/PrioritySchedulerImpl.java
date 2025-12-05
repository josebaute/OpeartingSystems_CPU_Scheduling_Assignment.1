package se.lnu.os.ht25.a1.required;

import java.util.ArrayDeque;
import java.util.List;

import se.lnu.os.ht25.a1.provided.Reporter;
import se.lnu.os.ht25.a1.provided.Scheduler;
import se.lnu.os.ht25.a1.provided.data.ProcessInformation;

public class PrioritySchedulerImpl implements Scheduler {

	private final Reporter reporter;
	private final long startingTime;

	private final ArrayDeque<ScheduledProcess> high = new ArrayDeque<>();
	private final ArrayDeque<ScheduledProcess> medium = new ArrayDeque<>();
	private final ArrayDeque<ScheduledProcess> low = new ArrayDeque<>();

	private Thread cpuThread;

	private ScheduledProcess currentProcess = null;

	private boolean running = true;

	private PrioritySchedulerImpl(Reporter r) {
		this.reporter = r;
		startingTime = System.currentTimeMillis();
	}

	private static class ScheduledProcess extends ProcessInformation{
		double remainingBurst;
		private int priority;

		ScheduledProcess(String name, int priority, double burst, double arrival){
			this.setProcessName(name);
			this.setCpuBurstDuration(burst);
			this.setArrivalTime(arrival);

			this.priority = priority;
			this.remainingBurst = burst;
		}

		public int getPriority() {
			return priority;
		}
	}

	// Factory method to create an instance of the scheduler
	public static Scheduler createInstance(Reporter reporter) {
		Scheduler s = (new PrioritySchedulerImpl(reporter)).initialize();
		return s;
	}

	// Fetches the report of all processes managed by the scheduler
	@Override
	public List<ProcessInformation> getProcessesReport() {
		return reporter.getProcessesReport();
	}

	private Scheduler initialize() {
		// TODO You have to write this method to initialize your Scheduler:
		// For instance, create the CPUthread, the ReporterManager thread, the necessary
		// queues lists/sets, etc.
		cpuThread = new Thread(() -> runCPULoop(), "CPU-Thread");
		cpuThread.start();
		return this;
	}

	/**
	 * Handles a new process to schedule from the user. When the user invokes it, a
	 * {@link ProcessInformation} object is created to record the process name,
	 * arrival time, and the length of the cpuBurst to schedule.
	 */
	@Override
	public void newProcess(String processName, int priority, double cpuBurstDuration) {
		// TODO You have to write this method.
		// Creamos un ScheduledProcess con arrivalTime = now()
		ScheduledProcess p = new ScheduledProcess(processName, priority, cpuBurstDuration, now());

		synchronized (this) {
			// Insertar en la cola adecuada (FIFO)
			if (priority == 0) {
				high.addLast(p);
			} else if (priority == 1) {
				medium.addLast(p);
			} else {
				low.addLast(p);
			}

			/*
			 * Si actualmente hay un proceso ejecutándose con prioridad mayor (número mayor
			 * => prioridad más baja) y el nuevo proceso tiene prioridad más alta (número
			 * menor), preemptamos.
			 */
			if (currentProcess != null && p.getPriority() < currentProcess.getPriority()) {
				// Interrumpimos el CPU thread para provocar InterruptedException en sleep()
				cpuThread.interrupt();
			}

			// Avisamos al CPU-thread que hay procesos disponibles
			this.notifyAll();
		}
	}

	/*
	 * This method may help you get the number of seconds since the execution
	 * started. Do not feel force to use it, only if you think that it helps your
	 * solution
	 */
	private double now() {
		return (System.currentTimeMillis() - startingTime) / 1000.0;
	}

	private void runCPULoop() {
		while (running) {
			ScheduledProcess next = null;

			// Obtener siguiente proceso disponible (prioridad 0 > 1 > 2)
			synchronized (this) {
				next = pollNextProcess();

				// Si no hay procesos, esperamos hasta que lleguen (wait será interrumpible)
				if (next == null) {
					try {
						this.wait();
					} catch (InterruptedException e) {
						// Si nos interrumpen mientras esperamos, simplemente reintentamos el bucle
					}
					continue;
				}
				// Marcamos currentProcess antes de salir del bloque sincronizado
				currentProcess = next;
			}

			// Ejecutar el proceso fuera del bloque synchronized
			executeProcess(next);
		}
	}

	// Saca (y devuelve) el siguiente proceso siguiendo prioridad FIFO
	private ScheduledProcess pollNextProcess() {
		if (!high.isEmpty()) return high.pollFirst();
		if (!medium.isEmpty()) return medium.pollFirst();
		if (!low.isEmpty()) return low.pollFirst();
		return null;
	}


	private void executeProcess(ScheduledProcess p) {
		// Si es la primera vez que entra al CPU, registrar cpuScheduledTime
		synchronized (this) {
			if (p.getCpuScheduledTime() == 0.0) {
				p.setCpuScheduledTime(now());
			}
		}

		double start = now();
		try {
			// Dormimos por el tiempo restante (en milisegundos)
			long sleepMs = (long) (p.remainingBurst * 1000);
			if (sleepMs > 0) {
				Thread.sleep(sleepMs);
			}

			// Si llegamos aquí, el proceso ha terminado normalmente
			p.setEndTime(now());

			// Informar al reporter (se pide que se almacenen los procesos que han terminado)
			reporter.addProcessReport(p);

			// Limpiar currentProcess
			synchronized (this) {
				currentProcess = null;
			}

		} catch (InterruptedException ie) {
			// Preemption ocurrió: calcular tiempo ejecutado y actualizar remainingBurst
			double executed = now() - start;
			if (executed < 0) executed = 0; // precaución
			synchronized (this) {
				p.remainingBurst = Math.max(0.0, p.remainingBurst - executed);

				// Reinsertar el proceso preempted al inicio de su cola (para que actúe como FIFO
				// dentro de su prioridad)
				if (p.getPriority() == 0) {
					high.addFirst(p);
				} else if (p.getPriority() == 1) {
					medium.addFirst(p);
				} else {
					low.addFirst(p);
				}

				// Limpiar currentProcess para indicar que CPU está libre (hasta que se saque de cola)
				currentProcess = null;

				// Notificar por si algún wait() está en curso (opcional)
				this.notifyAll();
			}
			// Continuar el loop para tomar el siguiente proceso (posiblemente el nuevo de mayor prioridad)
		}
	}
}
