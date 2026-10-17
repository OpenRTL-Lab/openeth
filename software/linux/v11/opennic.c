// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: software/linux/v11/opennic.c
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
/* OpenNIC ABI v1: independent platform driver for Linux 6.6. */
#include <linux/bitops.h>
#include <linux/delay.h>
#include <linux/dma-mapping.h>
#include <linux/etherdevice.h>
#include <linux/interrupt.h>
#include <linux/io.h>
#include <linux/module.h>
#include <linux/netdevice.h>
#include <linux/of.h>
#include <linux/platform_device.h>
#include "opennic_copy.h"
#include "opennic_copy_rx.h"

/* Default retains the published path; set only for paired experiments. */
static bool on_wide_copy;
module_param_named(wide_copy, on_wide_copy, bool, 0644);
MODULE_PARM_DESC(wide_copy, "Use aligned wide access on DMA copy side (experimental)");
static bool on_fix_rx_align;
module_param_named(fix_rx_align, on_fix_rx_align, bool, 0644);
MODULE_PARM_DESC(fix_rx_align, "Avoid duplicate NET_IP_ALIGN already reserved by napi_alloc_skb");
static bool on_merge_copy;
module_param_named(merge_copy, on_merge_copy, bool, 0644);
MODULE_PARM_DESC(merge_copy, "Merge aligned cached words on linear wide-copy TX (experimental)");
static bool on_merge_rx_copy;
module_param_named(merge_rx_copy, on_merge_rx_copy, bool, 0644);
MODULE_PARM_DESC(merge_rx_copy, "Merge DMA words into aligned cached RX stores (experimental)");

#define ON_SLOTS 32
#define ON_BUFFER 2048
#define ON_ID 0x00
#define ON_VERSION 0x04
#define ON_CONTROL 0x08
#define ON_STATUS 0x0c
#define ON_IRQ_MASK 0x10
#define ON_IRQ_STATUS 0x14
#define ON_TX_ADDR 0x20
#define ON_RX_ADDR 0x30
#define ON_TX_DONE 0x40
#define ON_TX_COUNT 0x44
#define ON_RX_DONE 0x48
#define ON_RX_COUNT 0x4c
#define ON_RX_STATUS 0x50
#define ON_SPACE 0x54
#define ON_BUSY (BIT(1) | BIT(2))

struct on_buffer {
	void *cpu;
	dma_addr_t dma;
	u16 bytes;
	bool active;
};

struct on_priv {
	struct net_device *netdev;
	struct device *dev;
	void __iomem *regs;
	struct napi_struct napi;
	spinlock_t tx_lock;
	struct on_buffer tx[ON_SLOTS], rx[ON_SLOTS];
	int irq[2];
	bool running;
	bool quarantined;
};

static u32 on_read(struct on_priv *p, unsigned int reg)
{
	return readl(p->regs + reg);
}

static void on_write(struct on_priv *p, unsigned int reg, u32 value)
{
	writel(value, p->regs + reg);
}

/* Caller serializes the staging bank. Hardware accepts POST atomically. */
static void on_post(struct on_priv *p, unsigned int bank,
		    struct on_buffer *b, unsigned int cookie, unsigned int bytes)
{
	dma_wmb();
	on_write(p, bank, lower_32_bits(b->dma));
	on_write(p, bank + 4, upper_32_bits(b->dma));
	on_write(p, bank + 8, (cookie << 16) | bytes);
	b->active = true;
	on_write(p, bank + 12, 1);
}

static void on_free_buffers(struct on_priv *p)
{
	unsigned int i;

	/* A failed quiescence check means buffers may still be DMA targets. */
	if (p->quarantined)
		return;
	for (i = 0; i < ON_SLOTS; i++) {
		if (p->tx[i].cpu)
			dma_free_coherent(p->dev, ON_BUFFER, p->tx[i].cpu, p->tx[i].dma);
		if (p->rx[i].cpu)
			dma_free_coherent(p->dev, ON_BUFFER, p->rx[i].cpu, p->rx[i].dma);
		memset(&p->tx[i], 0, sizeof(p->tx[i]));
		memset(&p->rx[i], 0, sizeof(p->rx[i]));
	}
}

static int on_alloc_buffers(struct on_priv *p)
{
	unsigned int i;

	for (i = 0; i < ON_SLOTS; i++) {
		p->tx[i].cpu = dma_alloc_coherent(p->dev, ON_BUFFER,
						&p->tx[i].dma, GFP_KERNEL);
		p->rx[i].cpu = dma_alloc_coherent(p->dev, ON_BUFFER,
						&p->rx[i].dma, GFP_KERNEL);
		if (!p->tx[i].cpu || !p->rx[i].cpu) {
			on_free_buffers(p);
			return -ENOMEM;
		}
	}
	return 0;
}

static void on_tx_completions(struct on_priv *p, bool wake)
{
	struct net_device *ndev = p->netdev;
	unsigned int i, packets = 0, bytes = 0;
	unsigned long flags;

	spin_lock_irqsave(&p->tx_lock, flags);
	for (i = 0; i < ON_SLOTS && on_read(p, ON_TX_COUNT); i++) {
		u32 done = on_read(p, ON_TX_DONE);
		unsigned int slot = done >> 16;
		struct on_buffer *b;

		if (slot >= ON_SLOTS || !p->tx[slot].active) {
			ndev->stats.tx_errors++;
			continue;
		}
		b = &p->tx[slot];
		packets++;
		bytes += b->bytes;
		if (done & 15) {
			ndev->stats.tx_errors++;
		} else {
			ndev->stats.tx_packets++;
			ndev->stats.tx_bytes += b->bytes;
		}
		b->active = false;
	}
	if (packets)
		netdev_completed_queue(ndev, packets, bytes);
	if (wake && packets && READ_ONCE(p->running) &&
	    netif_queue_stopped(ndev))
		netif_wake_queue(ndev);
	spin_unlock_irqrestore(&p->tx_lock, flags);
}

static int on_poll(struct napi_struct *napi, int budget)
{
	struct on_priv *p = container_of(napi, struct on_priv, napi);
	struct net_device *ndev = p->netdev;
	int work = 0;

	on_tx_completions(p, true);
	/* Linux may invoke poll with budget zero solely for TX cleanup. */
	if (!budget)
		return 0;
	while (work < budget && on_read(p, ON_RX_COUNT)) {
		u32 status = on_read(p, ON_RX_STATUS);
		u32 done = on_read(p, ON_RX_DONE);
		unsigned int slot = done >> 16, len = (done >> 4) & 0xfff;
		struct sk_buff *skb;

		work++;
		if (slot >= ON_SLOTS || !p->rx[slot].active) {
			ndev->stats.rx_errors++;
			continue;
		}
		p->rx[slot].active = false;
		if (status || len < ETH_HLEN || len > ON_BUFFER) {
			ndev->stats.rx_errors++;
			goto repost;
		}
		dma_rmb();
		skb = napi_alloc_skb(napi, len + NET_IP_ALIGN);
		if (!skb) {
			ndev->stats.rx_dropped++;
			goto repost;
		}
		/* napi_alloc_skb already reserves NET_SKB_PAD + NET_IP_ALIGN. */
		if (!READ_ONCE(on_fix_rx_align))
			skb_reserve(skb, NET_IP_ALIGN);
		if (READ_ONCE(on_wide_copy)) {
			if (READ_ONCE(on_merge_rx_copy))
				on_copy_from_dma_merge(skb_put(skb, len), p->rx[slot].cpu, len);
			else
				on_copy_from_dma(skb_put(skb, len), p->rx[slot].cpu, len);
		} else
			skb_put_data(skb, p->rx[slot].cpu, len);
		skb->protocol = eth_type_trans(skb, ndev);
		skb->ip_summed = CHECKSUM_NONE;
		ndev->stats.rx_packets++;
		ndev->stats.rx_bytes += len;
		napi_gro_receive(napi, skb);
repost:
		/* Exactly one descriptor consumed per completion; space must exist. */
		if (READ_ONCE(p->running) && (on_read(p, ON_SPACE) >> 16))
			on_post(p, ON_RX_ADDR, &p->rx[slot], slot, ON_BUFFER);
		else if (READ_ONCE(p->running))
			ndev->stats.rx_errors++;
	}
	if (work < budget && (on_read(p, ON_TX_COUNT) || on_read(p, ON_RX_COUNT)))
		return budget;
	if (work < budget && napi_complete_done(napi, work)) {
		if (READ_ONCE(p->running))
			on_write(p, ON_IRQ_MASK, 3);
		/* Pending level IRQ retriggers even if completion raced unmask. */
	}
	return work;
}

static irqreturn_t on_interrupt(int irq, void *data)
{
	struct net_device *ndev = data;
	struct on_priv *p = netdev_priv(ndev);

	if (!(on_read(p, ON_IRQ_STATUS) & 3))
		return IRQ_NONE;
	on_write(p, ON_IRQ_MASK, 0);
	if (READ_ONCE(p->running) && napi_schedule_prep(&p->napi))
		__napi_schedule(&p->napi);
	return IRQ_HANDLED;
}

static netdev_tx_t on_xmit(struct sk_buff *skb, struct net_device *ndev)
{
	struct on_priv *p = netdev_priv(ndev);
	unsigned int slot, len = max_t(unsigned int, skb->len, ETH_ZLEN);
	unsigned long flags;
	struct on_buffer *b;
	int copy_error;

	if (unlikely(len > ON_BUFFER)) {
		ndev->stats.tx_dropped++;
		dev_kfree_skb_any(skb);
		return NETDEV_TX_OK;
	}
	spin_lock_irqsave(&p->tx_lock, flags);
	for (slot = 0; slot < ON_SLOTS && p->tx[slot].active; slot++)
		;
	if (unlikely(!p->running || slot == ON_SLOTS ||
		     !(on_read(p, ON_SPACE) & 0xffff))) {
		netif_stop_queue(ndev);
		spin_unlock_irqrestore(&p->tx_lock, flags);
		return NETDEV_TX_BUSY;
	}
	b = &p->tx[slot];
	if (READ_ONCE(on_wide_copy) && !skb_is_nonlinear(skb)) {
		if (READ_ONCE(on_merge_copy))
			on_copy_to_dma_merge(b->cpu, skb->data, skb->len);
		else
			on_copy_to_dma(b->cpu, skb->data, skb->len);
		copy_error = 0;
	} else {
		copy_error = skb_copy_bits(skb, 0, b->cpu, skb->len);
	}
	if (unlikely(copy_error)) {
		ndev->stats.tx_dropped++;
		spin_unlock_irqrestore(&p->tx_lock, flags);
		dev_kfree_skb_any(skb);
		return NETDEV_TX_OK;
	}
	if (len > skb->len)
		memset(b->cpu + skb->len, 0, len - skb->len);
	b->bytes = len;
	netdev_sent_queue(ndev, len);
	on_post(p, ON_TX_ADDR, b, slot, len);
	/* Serialize stopping against completion/wake under the same lock. */
	for (slot = 0; slot < ON_SLOTS && p->tx[slot].active; slot++)
		;
	if (slot == ON_SLOTS)
		netif_stop_queue(ndev);
	spin_unlock_irqrestore(&p->tx_lock, flags);
	dev_consume_skb_any(skb);
	return NETDEV_TX_OK;
}

/* NAPI and TX submissions are disabled before calling this function. */
static bool on_quiesce(struct on_priv *p)
{
	unsigned long deadline = jiffies + msecs_to_jiffies(1000);
	unsigned int i;

	on_write(p, ON_CONTROL, 0);
	do {
		on_tx_completions(p, false);
		for (i = 0; i < ON_SLOTS && on_read(p, ON_RX_COUNT); i++) {
			u32 done = on_read(p, ON_RX_DONE);
			unsigned int slot = done >> 16;

			if (slot < ON_SLOTS)
				p->rx[slot].active = false;
		}
		if (!(on_read(p, ON_STATUS) & ON_BUSY)) {
			on_write(p, ON_CONTROL, 2);
			return true;
		}
		usleep_range(100, 200);
	} while (time_before(jiffies, deadline));
	return false;
}

static int on_open(struct net_device *ndev)
{
	struct on_priv *p = netdev_priv(ndev);
	unsigned int i;
	int ret;

	if (p->quarantined)
		return -EIO;
	on_write(p, ON_IRQ_MASK, 0);
	if (!on_quiesce(p)) {
		p->quarantined = true;
		return -EBUSY;
	}
	ret = on_alloc_buffers(p);
	if (ret)
		return ret;
	if ((on_read(p, ON_SPACE) & 0xffff) < ON_SLOTS ||
	    (on_read(p, ON_SPACE) >> 16) < ON_SLOTS) {
		on_free_buffers(p);
		return -ENOSPC;
	}
	netdev_reset_queue(ndev);
	napi_enable(&p->napi);
	on_write(p, ON_CONTROL, 1);
	for (i = 0; i < ON_SLOTS; i++)
		on_post(p, ON_RX_ADDR, &p->rx[i], i, ON_BUFFER);
	WRITE_ONCE(p->running, true);
	on_write(p, ON_IRQ_MASK, 3);
	/* Fixed internal MAC link; physical PHY setup is a separate device. */
	netif_carrier_on(ndev);
	netif_start_queue(ndev);
	return 0;
}

static int on_stop(struct net_device *ndev)
{
	struct on_priv *p = netdev_priv(ndev);
	unsigned long flags;

	netif_tx_disable(ndev);
	spin_lock_irqsave(&p->tx_lock, flags);
	WRITE_ONCE(p->running, false);
	spin_unlock_irqrestore(&p->tx_lock, flags);
	on_write(p, ON_IRQ_MASK, 0);
	synchronize_irq(p->irq[0]);
	synchronize_irq(p->irq[1]);
	napi_disable(&p->napi);
	/* Poll may have unmasked just before disabling; restore after joining. */
	on_write(p, ON_IRQ_MASK, 0);
	netif_carrier_off(ndev);
	if (!on_quiesce(p)) {
		p->quarantined = true;
		netdev_err(ndev, "DMA did not quiesce; retaining buffers until system reset\n");
	}
	on_free_buffers(p);
	netdev_reset_queue(ndev);
	return 0;
}

static const struct net_device_ops on_netdev_ops = {
	.ndo_open = on_open,
	.ndo_stop = on_stop,
	.ndo_start_xmit = on_xmit,
	.ndo_set_mac_address = eth_mac_addr,
	.ndo_validate_addr = eth_validate_addr,
};

static int on_probe(struct platform_device *pdev)
{
	struct net_device *ndev;
	struct on_priv *p;
	struct device_node *fixed;
	u32 speed = 0;
	int ret;

	fixed = of_get_child_by_name(pdev->dev.of_node, "fixed-link");
	if (!fixed)
		return dev_err_probe(&pdev->dev, -EINVAL, "fixed-link child required\n");
	of_property_read_u32(fixed, "speed", &speed);
	ret = speed == 1000 && of_property_read_bool(fixed, "full-duplex");
	of_node_put(fixed);
	if (!ret)
		return dev_err_probe(&pdev->dev, -EINVAL, "requires fixed 1000/full MAC link\n");
	ret = dma_set_mask_and_coherent(&pdev->dev, DMA_BIT_MASK(64));
	if (ret)
		return ret;
	ndev = alloc_etherdev(sizeof(*p));
	if (!ndev)
		return -ENOMEM;
	SET_NETDEV_DEV(ndev, &pdev->dev);
	p = netdev_priv(ndev);
	p->dev = &pdev->dev;
	p->netdev = ndev;
	spin_lock_init(&p->tx_lock);
	p->regs = devm_platform_ioremap_resource(pdev, 0);
	if (IS_ERR(p->regs)) {
		ret = PTR_ERR(p->regs);
		goto free_netdev;
	}
	if (on_read(p, ON_ID) != 0x4f4e4943 || on_read(p, ON_VERSION) != 0x10000) {
		ret = -ENODEV;
		goto free_netdev;
	}
	p->irq[0] = platform_get_irq(pdev, 0);
	p->irq[1] = platform_get_irq(pdev, 1);
	if (p->irq[0] < 0 || p->irq[1] < 0) {
		ret = p->irq[0] < 0 ? p->irq[0] : p->irq[1];
		goto free_netdev;
	}
	on_write(p, ON_IRQ_MASK, 0);
	on_write(p, ON_CONTROL, 0);
	ndev->netdev_ops = &on_netdev_ops;
	ndev->min_mtu = 68;
	ndev->max_mtu = ETH_DATA_LEN;
	ndev->features = 0;
	ndev->hw_features = 0;
	eth_hw_addr_random(ndev);
	netif_carrier_off(ndev);
	netif_napi_add(ndev, &p->napi, on_poll);
	ret = request_irq(p->irq[0], on_interrupt, 0, dev_name(&pdev->dev), ndev);
	if (ret)
		goto del_napi;
	ret = request_irq(p->irq[1], on_interrupt, 0, dev_name(&pdev->dev), ndev);
	if (ret)
		goto free_irq0;
	ret = register_netdev(ndev);
	if (ret)
		goto free_irq1;
	platform_set_drvdata(pdev, ndev);
	return 0;
free_irq1:
	free_irq(p->irq[1], ndev);
free_irq0:
	free_irq(p->irq[0], ndev);
del_napi:
	netif_napi_del(&p->napi);
free_netdev:
	free_netdev(ndev);
	return ret;
}

static void on_remove(struct platform_device *pdev)
{
	struct net_device *ndev = platform_get_drvdata(pdev);
	struct on_priv *p = netdev_priv(ndev);

	unregister_netdev(ndev);
	on_write(p, ON_IRQ_MASK, 0);
	free_irq(p->irq[1], ndev);
	free_irq(p->irq[0], ndev);
	netif_napi_del(&p->napi);
	/* Timed-out buffers deliberately remain allocated; no future DMA UAF. */
	on_free_buffers(p);
	free_netdev(ndev);
}

static const struct of_device_id on_of_match[] = {
	{ .compatible = "openhw,opennic-v1" },
	{ }
};
MODULE_DEVICE_TABLE(of, on_of_match);

static struct platform_driver on_driver = {
	.probe = on_probe,
	.remove_new = on_remove,
	.driver = { .name = "opennic", .of_match_table = on_of_match },
};
module_platform_driver(on_driver);
MODULE_DESCRIPTION("OpenNIC v1.1 experimental wide-copy Ethernet driver");
MODULE_VERSION("1.1.0-dev4");
MODULE_LICENSE("Dual MIT/GPL");
